(ns projeto-backend-clojure.model
  (:require [clojure.string :as str]))

;; ----------------------------
;; Rooms setup
;; ----------------------------
(def allowed-rooms ["Kitchen" "Living Room" "Bedroom" "Office" "Restroom" "DiningRoom"])
(def room->id (zipmap allowed-rooms (range)))
(def id->room (zipmap (range) allowed-rooms))

(defn room-encoder [room]
  (some-> room str/trim (get room->id)))

(defn room-decoder [id] (get id->room id "Unknown"))
;; ----------------------------
;; Day of week + Time of day encoders
;; ----------------------------

(def day->id
  {"Mon" 1, "Tue" 2, "Wed" 3, "Thu" 4, "Fri" 5, "Sat" 6, "Sun" 7})

(def id->day
  (zipmap (vals day->id) (keys day->id)))

(defn day-encoder [day]
  (some-> day str/trim (get day->id)))

(defn day-decoder [id]
  (get id->day id "Unknown"))

(defn time-encoder
  "Encodes a time string HH:MM into a half-hour slot index (0–47).
   Example: \"00:00\" -> 0, \"00:30\" -> 1, \"13:00\" -> 26, \"23:30\" -> 47."
  [t]
  (when (and (string? t) (re-matches #"\d{1,2}:?\d{2}" t))
    (let [clean (clojure.string/replace t ":" "")
          hh    (Integer/parseInt (subs clean 0 2))
          mm    (Integer/parseInt (subs clean 2 4))]
      (+ (* hh 2) (quot mm 30)))))

(defn time-decoder
  "Decodes a half-hour slot index (0–47) back to HH:MM string."
  [id]
  (when (and (int? id) (<= 0 id 47))
    (let [hh (quot id 2)
          mm (* 30 (mod id 2))]
      (format "%02d:%02d" hh mm))))


;; ----------------------------
;; Helpers
;; ----------------------------
(defn- numeric-current [v]
  (cond
    (number? v) v
    (boolean? v) (if v 1 0)
    (string? v) (try (Double/parseDouble v) (catch Exception _ 0))
    :else 0))

(defn- blank?* [v]
  (or (nil? v)
      (and (string? v) (str/blank? v))))

;; ----------------------------
;; Raw GP helpers (1D input)
;; ----------------------------
(defn- rbf [x1 x2 l sigma-f]
  (* sigma-f sigma-f (Math/exp (- (/ (Math/pow (- x1 x2) 2) (* 2 l l))))))

(defn- naive-gp-predict [xs ys x*]
  ;; naive 1D GP: mean = sum(k(x*, xi) * yi) / sum(k(x*, xi))
  (let [l 1.0
        sigma-f 1.0
        kx (map #(rbf x* % l sigma-f) xs)
        weighted-sum (reduce + (map * kx ys))
        k-sum (reduce + kx)]
    (if (zero? k-sum)
      (last ys)
      (/ weighted-sum k-sum))))

;; ----------------------------
;; Logarithmic sampling
;; ----------------------------
(defn- sample-rows
  "Sample rows based on logarithmic decay, capped at 500."
  [pairs]
  (let [n (count pairs)]
    (cond
      (< n 2) pairs
      :else
      (let [c 1.0
            k 10.0
            frac (/ c (Math/log (+ n k)))
            m (min 500 (max 2 (int (Math/round (* frac n)))))]
        (vec (take m (shuffle pairs)))))))

;; ----------------------------
;; Main prediction
;; ----------------------------
(defn predict-next [rows]
  (let [currents (map numeric-current (map :current rows))
        rooms (map :room rows)
        pairs (vec (keep (fn [[x y]]
                           (let [yid (room-encoder y)]
                             (when (and (some? x) (some? yid))
                               [x yid])))
                         (map vector currents rooms)))
        sampled (sample-rows pairs)
        xs (mapv first sampled)
        ys (mapv second sampled)
        last-current (last currents)
        ;; majority-based current prediction
        ones (count (filter pos? currents))
        zeros (- (count currents) ones)
        pred-current (cond
                       (> ones zeros) 1
                       (> zeros ones) 0
                       :else (rand-nth [0 1]))   ;; 👈 tie → random
        ;; raw GP room prediction with fallback to RANDOM instead of last known
        pred-room (if (seq sampled)
                    (room-decoder (int (Math/round (naive-gp-predict xs ys last-current))))
                    (rand-nth allowed-rooms))]  ;; 👈 random fallback
    {:pred-current pred-current
     :pred-room pred-room
     :model {:training-size (count sampled)
             :total-size (count pairs)}}))
;; ----------------------------
;; Extended prediction wrapper
;; ----------------------------

(defn predict-next-extended
  "Wrapper around predict-next that also considers day and time encoders.
   Case 1: if :current, :room, :day, :time are present -> train with all 4 and predict.
   Case 2: if only :current and :time are present, fill missing values with prediction."
  [rows]
  (let [last-row (last rows)
        c (:current last-row)
        r (:room last-row)
        d (:day last-row)
        t (:time last-row)
        ;; Encoded values
        did (day-encoder d)
        tid (time-encoder t)
        base-pred (predict-next rows)]

    (cond
      ;; Case 1: full row present, train and predict, then self-correct if wrong
      (and (some? c) (not (blank?* c))
           (some? r) (not (blank?* r))
           (some? d) (not (blank?* d))
           (some? t) (not (blank?* t)))
      (let [pred-c (:pred-current base-pred)
            pred-r (:pred-room base-pred)
            corrected (if (and (not= pred-c c)
                               (not= pred-r r))
                        ;; correction step: override with actual last known row
                        {:pred-current c
                         :pred-room r
                         :model (:model base-pred)}
                        base-pred)]
        corrected)

      ;; Case 2: only current + time given, fill others with prediction
      (and (some? c) (not (blank?* c))
           (some? t) (not (blank?* t))
           (or (blank?* r) (blank?* d)))
      {:pred-current c
       :pred-room (:pred-room base-pred)
       :pred-day (or d (day-decoder did))
       :pred-time (or t (time-decoder tid))
       :model (:model base-pred)}

      ;; Fallback: just run normal model
      :else base-pred)))
(defn predict-next-for-all-rooms
  "Wrapper around predict-next-extended.
   Runs a prediction for each allowed room, producing N rows at once."
  [rows]
  (let [day  (.toString (java.time.LocalDate/now))
        time (.toString (java.time.LocalTime/now))]
    (map (fn [room]
           (let [;; extend rows by faking the next row’s room
                 augmented-rows (conj rows {:room room})
                 pred           (predict-next-extended augmented-rows)]
             {:pred-current (:pred-current pred)
              :pred-room    room
              :pred-day     day
              :pred-time    time}))
         allowed-rooms)))



