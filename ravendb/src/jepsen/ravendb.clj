(ns jepsen.ravendb
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]]
            [knossos.model :as model]
            [jepsen
             [checker :as checker]
             [cli :as cli]
             [core :as core]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [independent :as independent]
             [nemesis :as nemesis]
             [tests :as tests]
             [util :as util
              :refer   [timeout]]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian])
  (:import [net.ravendb.client.documents DocumentStore]
           [net.ravendb.client.serverwide DatabaseRecord]
           [net.ravendb.client.documents.operations GetCompareExchangeValueOperation]
           [net.ravendb.client.documents.operations.configuration
            GetClientConfigurationOperation]
           [net.ravendb.client.documents.operations.configuration
            PutClientConfigurationOperation]
           [net.ravendb.client.documents.operations PutCompareExchangeValueOperation]
           [net.ravendb.client.documents.commands.batches BatchCommand]
           [net.ravendb.client.documents.commands PutDocumentCommand]
           [net.ravendb.client.serverwide ClientConfiguration]
           [net.ravendb.client.documents.commands GetDocumentsCommand]
           [net.ravendb.client.documents.commands.batches PutCommandDataWithJson]
           [net.ravendb.client.extensions JsonExtensions]
           [net.ravendb.client.serverwide.operations CreateDatabaseOperation]))

(def dir "/opt/ravendb")
(def serverdir (str dir "/Server"))

(def tarurl
  "https://daily-builds.s3.amazonaws.com/RavenDB-4.0.0-nightly-20180124-0500-linux-x64.tar.bz2")

;;(def tarurl "file:///root/raven.tar.bz2")
(def binary "Raven.Server")
(def logfile (str dir "/ravendb.log"))
(def pidfile (str dir "/ravendb.pid"))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})

(defn node-url
  "An HTTP url for connecting to a node on a particular port."
  [node port]
  (str "http://" (name node) ":" port))

(defn node-port
  "Port per given node"
  [node]
  (case node
    "n1" 8091
    "n2" 8092
    "n3" 8093
    "n4" 8094
    "n5" 8095)
  )

(defn server-url
  "The HTTP url clients use to talk to a node."
  [node]
  (node-url node (node-port node)))

(defn tcp-url
  "The TCP url clients use to talk to a node."
  [node]
  (str "tcp://" (name node) ":" 38080))

(defn join-cluster
  "Joins to cluster"
  [test node]
  (info node "joining")
  (def url
    (str
     (server-url
      (core/primary test))
     "/admin/cluster/node?assignedCores=1&url="
     (server-url node)))
  (info url "urlToJoin")
  ;; ex. curl -i -X PUT -d "" "http://n1:8080/admin/cluster/node?assignedCores=1&url=http://n5:8080"
  (c/exec :curl :-L :-i :-X :PUT :-o (str "/root/" node ".log") :-d "" url))


(defrecord ConfigurationClient [node store]
  client/Client

  (open! [client test n]
         (let [store  (DocumentStore. (into-array String [(server-url n)]) "jepsen")]
           (.initialize store)
           (assoc client :store store :node n)))

  (setup! [this test])

  (invoke! [this test op]
           ; Reads are idempotent; if they fail we can always assume they didn't
           ; happen in the history, and reduce the number of hung processes, which
           ; makes the knossos search more efficient
           (let [fail (if (= :read (:f op))
                        :fail
                        :info)]
             (try
               (case (:f op)
                 :read
                 (let [store   (:store this)
                       cmd     (GetClientConfigurationOperation.)
                       result  (.send (.maintenance store) cmd)
                       r       (.getConfiguration result)]

                   (if (nil? r)
                     (assoc op :type :fail, :error :null-read)
                     (assoc op :type :ok, :value (.getMaxNumberOfRequestsPerSession r))))


                 :write (let [store (:store this)
                              [k v] (:value op)
                              conf  (ClientConfiguration.)]
                          (.setMaxNumberOfRequestsPerSession conf v)

                          (.send (.maintenance store) (PutClientConfigurationOperation. conf))

                          (assoc op :type :ok)))
               (catch java.net.SocketTimeoutException e
                 (assoc op :type fail :error :timed-out))

               (catch net.ravendb.client.exceptions.ConcurrencyException e
                 (assoc op :type fail :error :concurrency))

               (catch Exception e
                 (assoc op :type fail :error (.getMessage e))))))

  (teardown! [this test]
             (.close (:store this)))

  (close! [client test]))

(defrecord DocumentClient [node store]
  client/Client

  (open! [client test n]
         (let [store  (DocumentStore. (into-array String [(server-url n)]) "jepsen")]
           (.initialize store)
           (assoc client :store store :node n)))

  (setup! [this test])

  (invoke! [this test op]
           ; Reads are idempotent; if they fail we can always assume they didn't
           ; happen in the history, and reduce the number of hung processes, which
           ; makes the knossos search more efficient
           (let [fail (if (= :read (:f op))
                        :fail
                        :info)]
             (try
               (case (:f op)
                 :read

                 (let [store   (:store this)
                       readCmd (GetDocumentsCommand. "jepsen" nil false)]

                   (.execute (.getRequestExecutor store) readCmd)
                   (let [r (.getResult readCmd)
                         rr (.getResults r)
                         n1 (.get rr 0)
                         max (.get n1 "maxNumberOfRequestsPerSession")
                         textValue (.asInt max)
                         ]

                     (assoc op :type :ok, :value textValue)

                     ))

                 :write
                 (let [store  (:store this)
                       [k v]  (:value op)
                       mapper (JsonExtensions/getDefaultEntityMapper)
                       conf   (ClientConfiguration.)]

                   (.setMaxNumberOfRequestsPerSession conf v)

                   (let [json     (.valueToTree mapper conf)
                         cmd      (PutCommandDataWithJson. "jepsen" nil json)
                         batchCmd (BatchCommand. (.getConventions store) (java.util.ArrayList. [cmd]))]

                     ;;store1.getRequestExecutor().execute();
                     (.execute (.getRequestExecutor store) batchCmd)
                     (assoc op :type :ok))))
               (catch java.net.SocketTimeoutException e
                 (assoc op :type fail :error :timed-out))

               (catch net.ravendb.client.exceptions.ConcurrencyException e
                 (assoc op :type fail :error :concurrency))

               (catch Exception e
                 (assoc op :type fail :error (.getMessage e))))
             ))


  (teardown! [this test]
             (.close (:store this)))

  (close! [client test])

  )


(defn document-client
  "A client for RavenDB"
  [conn]
  (DocumentClient. conn nil))


(defn config-client
  "A client for RavenDB"
  [conn]
  (ConfigurationClient. conn nil))

(defn db
  "RavenDB database."
  []
  (reify
   db/DB
   (setup! [_ test node]
           (c/su
            (info node "installing RavenDB")

            (c/exec :apt-get :install :libunwind8 :-y)

            (cu/install-archive! tarurl dir)

            ;; copy license to each host
            (c/upload "/root/license.json" (str serverdir "/license.json"))

            (cu/start-daemon!
             {:logfile logfile
              :pidfile pidfile
              :chdir   serverdir}
             binary
             (str "--ServerUrl=" (server-url node))
             "--Security.UnsecuredAccessAllowed=PublicNetwork"
             "--License.Eula.Accepted=true"
             "--Setup.Mode=None"
             (str "--ServerUrl.Tcp=" (tcp-url node)))

            (Thread/sleep 5000))

           (core/synchronize test)

           (when (= node (core/primary test))
             (doseq [f (:nodes test)]
               (when (not= f (core/primary test))
                 (join-cluster test f)
                 (Thread/sleep 3000)))


             (let [store  (DocumentStore. (into-array String [(server-url node)]) "jepsen")]
               (.initialize store)

               (try
                 (let [dbRecord (DatabaseRecord.)]
                   (.setDatabaseName dbRecord "jepsen")
                   (let [op (CreateDatabaseOperation. dbRecord 5)]
                     (.send (.server (.maintenance store)) op)))

                 (catch Exception e
                   (warn e "Error creating database")))))

           (Thread/sleep 3000)
           (core/synchronize test))

   (teardown! [_ test node]
              (info node "tearing down RavenDB")
              (cu/stop-daemon! binary pidfile)
              (c/su
               (c/exec :rm :-rf dir)))

   db/LogFiles
   (log-files [_ test node]
              [logfile])))

(defn ravendb-test
  "Given an options map from the command-line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (info :opts opts)
  (merge tests/noop-test
         {:name      "ravendb"
          :os        debian/os
          :db        (db)
          ;:client    (config-client nil) ;; document-client or config-client
          :nemesis   (nemesis/partition-random-halves)
          :model     (model/register)
          ;;:checker   (checker/compose
          ;;            {:perf  (checker/perf)
          ;;             :indep (independent/checker
          ;;                     (checker/compose
          ;;                      {:timeline (timeline/html)
          ;;                       :linear   (checker/linearizable)}))})
          :generator (->>
                      (gen/nemesis
                       (gen/seq
                        (cycle
                         [(gen/sleep 30)
                          {:type :info, :f :start}
                          (gen/sleep 20)
                          {:type :info, :f :stop}])))
                      (gen/time-limit 7200))}
         opts))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run!
   (merge (cli/single-test-cmd {:test-fn ravendb-test})
          (cli/serve-cmd))
   args))



