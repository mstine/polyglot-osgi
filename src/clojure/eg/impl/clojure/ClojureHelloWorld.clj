(ns eg.impl.clojure.ClojureHelloWorld
	(:gen-class
		:implements [eg.api.HelloWorld]
	)
)

(defn -greet [this]
	(println "Hello, World!" (str (class this))))
