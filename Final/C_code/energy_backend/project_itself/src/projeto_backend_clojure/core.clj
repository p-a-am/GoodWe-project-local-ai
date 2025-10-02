(ns projeto-backend-clojure.core
  (:gen-class)
  (:require [projeto-backend-clojure.csv-io :as csv-io]
            [projeto-backend-clojure.model :as model]
            [projeto-backend-clojure.api :as api]
            [clojure.java.io :as io]))

(def last-mod-time (atom 0))

(defonce path
  (or (System/getenv "DATA_CSV")
      "/home/pam/smartlights/C_code/energy_backend/project_itself/src/projeto_backend_clojure/resources/GoodWe_database.csv"))

(def day->id
  {"Mon" 1, "Tue" 2, "Wed" 3, "Thu" 4, "Fri" 5, "Sat" 6, "Sun" 7})

(def id->day
  (zipmap (vals day->id) (keys day->id)))

(defn get-next-day [current-day]
  (let [current-id (get day->id current-day)
        next-id (if (= current-id 7) 1 (inc current-id))]
    (get id->day next-id)))

(defn run-once! []
  (println "🚀 [CORE] run-once! starting...")
  (let [rows (csv-io/read-rows path)]
    (println "📂 [CORE] Loaded" (count rows) "rows from" path)
    (when (seq rows)
      (println "📂 [CORE] Last row =" (last rows)))
    (doseq [pred (model/predict-next-for-all-rooms rows)]
      (let [last-row (last rows)
            last-day (:day last-row)
            corrected-pred (assoc pred :pred-day (get-next-day last-day))]
        (println "🤖 [CORE] Prediction =" corrected-pred)
        (csv-io/write-next-row-prediction! path
                                           {:current (:pred-current corrected-pred)
                                            :room    (:pred-room corrected-pred)
                                            :day     (:pred-day corrected-pred)
                                            :time    (:pred-time corrected-pred)})))
    (reset! last-mod-time (.lastModified (io/file path)))
    (println "✅ [CORE] Finished run-once! updated last-mod-time =" @last-mod-time)))

(defn -main [& _]
  (println "🌐 [CORE] Starting Smart Lights backend with path=" path)

  ;; Start the API server
  (future (api/start-server! run-once!))

  ;; Main loop for polling file changes
  (loop []
    (let [mod-time (.lastModified (io/file path))]
      (when (> mod-time @last-mod-time)
        (println "🔔 [CORE] File change detected! Running predictions.")
        (reset! last-mod-time mod-time)
        (run-once!)))
    (Thread/sleep 200) ;; Check for changes every 2 milisseconds
    (recur)))