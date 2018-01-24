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
           [net.ravendb.client.documents.operations PutCompareExchangeValueOperation]
           [net.ravendb.client.serverwide.operations CreateDatabaseOperation]))

(def dir "/opt/ravendb")
(def serverdir (str dir "/Server"))

(def tarurl   "https://daily-builds.s3.amazonaws.com/RavenDB-4.0.0-nightly-20180124-0500-linux-x64.tar.bz2")
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

(defn server-url
  "The HTTP url clients use to talk to a node."
  [node]
  (node-url node 8080))

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


(defrecord RachisClient [node store]
  client/Client

  (open! [client test n]
         (let [store  (DocumentStore. (into-array String [(server-url n)]) "jepsen")]
           (.initialize store)
           (assoc client :store store :node n)))

  (setup! [this test]
          (println "setup!")
          )

  (invoke! [this test op]
           ; Reads are idempotent; if they fail we can always assume they didn't
           ; happen in the history, and reduce the number of hung processes, which
           ; makes the knossos search more efficient
           (let [fail (if (= :read (:f op))
                        :fail
                        :info)]
             (try+
               (case (:f op)
                 :read  (let
                          [store (:store this)
                           cmd (GetCompareExchangeValueOperation. Integer "jepsen")
                           result  (.send (.operations store) cmd)
                           ]

                          (if (nil? result)
                            (assoc op :type :fail :value nil)
                            (assoc op :type :ok :value (.getValue result))))

                 :write (let
                          [store (:store this)
                           [k v] (:value op)
                           cmd (PutCompareExchangeValueOperation. "jepsen" v 0)
                           result  (.send (.operations store) cmd)]
                          (assoc op :type :ok))
                 )
               (catch java.net.SocketTimeoutException e
                 (assoc op :type fail :value :timed-out))

              ;;(catch net.ravendb.client.exceptions.RavenException e
              ;;  (assoc op :type fail :value :timed-out))
               )))

  (teardown! [this test]
             (.close (:store this))
             )

  (close! [client test]
          ))



(defn client
  "A client for RavenDB"
  [conn]
  (RachisClient. conn nil))

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
                   (warn e "Error creating database"))
                 )

               )
             )

           (core/synchronize test)


       )

   (teardown! [_ test node]
              (info node "tearing down RavenDB")
              (cu/stop-daemon! binary pidfile)
              (c/su
               (c/exec :rm :-rf dir))
              )

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
          :client    (client nil)
          :nemesis   (nemesis/partition-random-halves)
          :model     (model/cas-register)
          :checker   (checker/compose
                      {:perf  (checker/perf)
                       :indep (independent/checker
                               (checker/compose
                                {:timeline (timeline/html)
                                 :linear   (checker/linearizable)}))})
          :generator (->>
                      (independent/concurrent-generator
                       10
                       (range)
                       (fn [k]
                         (->> (gen/mix [r w])
                              (gen/stagger 1/30)
                              (gen/limit 300))))
                      (gen/nemesis
                       (gen/seq
                        (cycle
                         [(gen/sleep 5)
                          {:type :info, :f :start}
                          (gen/sleep 5)
                          {:type :info, :f :stop}])))
                      (gen/time-limit (:time-limit opts)))}
         opts))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run!
   (merge (cli/single-test-cmd {:test-fn ravendb-test})
          (cli/serve-cmd))
   args))



