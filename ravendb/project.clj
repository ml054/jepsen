(defproject jepsen.ravendb "0.1.0-SNAPSHOT"
  :description "A Jepsen test for RavenDB"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main jepsen.ravendb
  :jvm-opts ["-Dcom.sun.management.jmxremote"]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [jepsen "0.1.7-SNAPSHOT"]
                 [net.ravendb/ravendb "4.0.0"]])
