(ns eg.impl.clojure.ClojureHelloWorldBundle
	(:gen-class
		:extends eg.osgi.helpers.HelloWorldBundle
	)
)

(defn getHelloWorld [this]
	(new eg.impl.clojure.ClojureHelloWorld)
)

