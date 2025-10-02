(ns projeto-backend-clojure.csv-io
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ----------------------------
;; Helpers
;; ----------------------------
(defn- parse-bool [s]
  (cond
    (nil? s) false
    (str/blank? s) false
    (#{"true" "1" "yes" "on"} (str/lower-case (str/trim s))) true
    :else false))

(defn- blank?* [s]
  (or (nil? s) (str/blank? (str s))))

(defn- format-bool [b]
  (if b "true" "false"))

(defn- format-num [x]
  (when (some? x)
    (format "%.4f" (double x))))

;; ----------------------------
;; CSV Reading
;; ----------------------------
(defn read-rows
  "Reads CSV into a vector of maps keyed by the header row (lowercased)."
  [file]
  (with-open [reader (io/reader file)]
    (let [[header & rows] (csv/read-csv reader :separator \;)]
      (let [header-keys (mapv #(-> % (or "") str/trim str/lower-case keyword) header)]
        (mapv (fn [row]
                (zipmap header-keys
                        (map (fn [k v]
                               (if (= k :current)
                                 (parse-bool v)
                                 (-> v (or "") str/trim)))
                             header-keys row)))
              rows)))))

;; ----------------------------
;; CSV Writing
;; ----------------------------
(def min-rows 500)
(def max-rows 600)

(defn- exponential-trim [rows]
  (let [n (count rows)]
    (cond
      ;; ≤ 600 rows → keep everything
      (<= n max-rows) rows

      ;; > 600 rows → exponential delete
      :else
      (let [recent (subvec rows (- n min-rows)) ;; always keep last 500
            old    (subvec rows 0 (- n min-rows))
            lambda 0.01] ;; decay factor — tune as needed
        (vec (concat
              ;; keep older rows with decreasing probability
              (for [[idx row] (map-indexed vector old)
                    :when (< (rand) (Math/exp (* -1 lambda idx)))]
                row)
              recent))))))

(defn write-next-row-prediction!
  "Fills the next row where :current, :room, :day, or :time is blank.
   If the CSV grows above 600 rows, old rows are deleted exponentially,
   always keeping the most recent 500 rows."
  [file {:keys [current room day time]}]
  (let [rows (vec (read-rows file))]
    (if (empty? rows)
      (println "No rows to update.")
      (let [header (vec (keys (first rows)))
            row-idx (first
                     (keep-indexed
                      (fn [i row]
                        (when (or (blank?* (:current row))
                                  (blank?* (:room row))
                                  (blank?* (:day row))
                                  (blank?* (:time row)))
                          i))
                      rows))]
        (if (nil? row-idx)
          (println "No blank slots found.")
          (let [row (rows row-idx)
                updates (cond-> {}
                          (and (blank?* (:current row)) (some? current)) (assoc :current current)
                          (and (blank?* (:room row)) (some? room))       (assoc :room room)
                          (and (blank?* (:day row)) (some? day))         (assoc :day day)
                          (and (blank?* (:time row)) (some? time))       (assoc :time time))
                updated (merge row updates)
                updated-rows (assoc rows row-idx updated)
                trimmed (exponential-trim updated-rows)]
            (with-open [w (io/writer file)]
              (csv/write-csv
               w
               (cons (map name header)
                     (map (fn [r]
                            (map (fn [k]
                                   (cond
                                     (= k :current) (if (:current r) "true" "false")
                                     (= k :room)    (:room r)
                                     (= k :day)     (:day r)
                                     (= k :time)    (:time r)
                                     :else          (get r k "")))
                                 header))
                          trimmed))
               :separator \;))
            (println "Updated row" (inc row-idx) "in" file
                     " -> total rows now:" (count trimmed))))))))