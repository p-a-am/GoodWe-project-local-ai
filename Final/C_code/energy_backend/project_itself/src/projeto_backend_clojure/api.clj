(ns projeto-backend-clojure.api
  (:require [org.httpkit.server :as http]
            [clojure.data.json :as json]))

(defn parse-json-body [body]
  (json/read-str (slurp body)))

(defn handler-factory [on-save-callback]
  (fn [req]
    (let [parsed (parse-json-body (:body req))]
      (on-save-callback)
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:message "Snapshot saved successfully"})})))

(defn start-server! [on-save-callback]
  (http/run-server (handler-factory on-save-callback) {:port 8080})
  (println "API server running on port 8080"))