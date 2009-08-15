package eg.impl.groovy16;

import eg.api.*;
import eg.osgi.helpers.*;
import org.osgi.framework.*;

class Groovy16HelloWorldBundle extends HelloWorldBundle {

	HelloWorld getHelloWorld() { new Groovy16HelloWorld() }

	void start(BundleContext context) {
		ClassLoader originalClassLoader = Thread.currentThread().contextClassLoader
		try {
			Thread.currentThread().contextClassLoader = getClass().classLoader
			super.start()
		} finally {
			Thread.currentThread().contextClassLoader = originalClassLoader
		}
	}

}

