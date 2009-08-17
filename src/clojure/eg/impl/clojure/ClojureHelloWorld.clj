(ns eg.impl.clojure.ClojureHelloWorld
	(:gen-class
	)
)

(defn -greet [this]
	(println "Hello, World!" (str (class this))))
