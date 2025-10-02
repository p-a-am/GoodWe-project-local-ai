(ns projeto-backend-clojure.watcher
  (:import [java.nio.file FileSystems Paths StandardWatchEventKinds WatchEvent]))

(defn watch-file
  "Watches a file and updates an atom whenever it is modified."
  [path events-atom]
  (let [watcher (.newWatchService (FileSystems/getDefault))
        file-path (Paths/get path (into-array String []))
        dir (.getParent file-path)
        target-fn (str (.getFileName file-path))]
    (.register dir watcher (into-array [StandardWatchEventKinds/ENTRY_MODIFY]))
    (future
      (while true
        (let [key (.take watcher)]
          (doseq [event (.pollEvents key)]
            (let [changed ^java.nio.file.Path (.context ^WatchEvent event)
                  changed-name (str changed)]
              (when (= changed-name target-fn)
                (println "🔔 [WATCHER] File change detected. Updating atom.")
                (swap! events-atom (fn [old-val] (inc (or old-val 0)))))))
          (.reset key))))))