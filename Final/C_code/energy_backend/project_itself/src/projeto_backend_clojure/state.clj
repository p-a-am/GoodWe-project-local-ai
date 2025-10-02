(ns projeto-backend-clojure.state
  (:require [projeto-backend-clojure.csv-io :as csv-io]))

(defonce app-state (atom {}))

(defn data-path
  "Returns the path to the CSV file from the centralized app state."
  []
  (:csv-path @app-state))

(defn fill-snapshot-keep!
  "Writes room state predictions to the CSV file, using an explicit file path."
  [file-path rooms-map]
  (let [day  (.toString (java.time.LocalDate/now))
        time (.toString (java.time.LocalTime/now))]
    (doseq [[room state] rooms-map]
      (csv-io/write-next-row-prediction!
       file-path {:current state
                  :room    room
                  :day     day
                  :time    time}))))

(defn fill-snapshot-default!
  "Writes room state predictions using the default CSV path (from data-path)."
  [rooms-map]
  (fill-snapshot-keep! (data-path) rooms-map))