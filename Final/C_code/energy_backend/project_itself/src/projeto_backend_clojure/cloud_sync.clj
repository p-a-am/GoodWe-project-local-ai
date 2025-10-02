(ns projeto-backend-clojure.cloud-sync
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]))

(def prediction-count (atom 0))
(def max-uploads 4096) ;; cap at 4096 runs

(defn- s3-cp [args]
  (let [{:keys [exit out err]} (apply shell/sh "aws" "s3" args)]
    (if (zero? exit)
      (do (when (seq out) (println out)) true)
      (do (println "❌ AWS CLI error:" err) false))))

(defn- upload-file-to-s3! [file bucket key]
  (when (.exists (io/file file))
    (println "☁️ Uploading" (.getName (io/file file)) "→" bucket "/" key)
    (s3-cp ["cp" file (str "s3://" bucket "/" key)])))

(defn- download-file-from-s3! [bucket key file]
  (println "☁️ Attempting to download" key "from bucket" bucket "→" file)
  (s3-cp ["cp" (str "s3://" bucket "/" key) file]))

(defn power-of-two? [n]
  (= n (bit-and n (- n))))

;; called at startup
(defn download-or-init-model! []
  (let [bucket (or (System/getenv "S3_BUCKET") "my-smartlights-backup")
        model-file (io/file "resources/model.edn")]
    (if (download-file-from-s3! bucket "models/model-latest.edn" (.getPath model-file))
      (println " Model downloaded successfully from S3.")
      (do
        (println " No model found in S3. Uploading initial empty model…")
        ;; create a temporary fresh model in memory
        (let [tmp-file (io/file "resources/tmp-init-model.edn")]
          (.mkdirs (.getParentFile tmp-file))
          (spit tmp-file (pr-str {:weights [] :created (java.time.Instant/now)}))
          ;; upload as first snapshot
          (upload-file-to-s3! tmp-file bucket "models/model-1.edn")
          (upload-file-to-s3! tmp-file bucket "models/model-latest.edn")
          (reset! prediction-count 1)
          ;; now download the "latest" so local copy matches cloud
          (download-file-from-s3! bucket "models/model-latest.edn" (.getPath model-file)))
        (println " Initial model seeded in S3 and synced locally.")))))

;; called after each training run
(defn upload-model-logarithmic! []
  (let [bucket (or (System/getenv "S3_BUCKET") "my-smartlights-backup")
        model-file "resources/model.edn"
        n (swap! prediction-count inc)]
    (cond
      (> n max-uploads)
      (println " Upload cap reached at" max-uploads ", skipping further uploads.")

      (and (power-of-two? n) (.exists (io/file model-file)))
      (do
        ;; versioned upload
        (upload-file-to-s3! model-file bucket (str "models/model-" n ".edn"))
        ;; overwrite "latest"
        (upload-file-to-s3! model-file bucket "models/model-latest.edn")))))
