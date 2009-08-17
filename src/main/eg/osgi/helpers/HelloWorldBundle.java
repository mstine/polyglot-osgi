package eg.osgi.helpers;

import eg.api.HelloWorld;
import org.osgi.framework.*;
import org.apache.felix.framework.util.StringMap;

public abstract class HelloWorldBundle implements BundleActivator {

	private ServiceRegistration registration;

	protected abstract HelloWorld getHelloWorld();

	public void start(BundleContext context) throws Exception {
		HelloWorld impl = getHelloWorld();
		System.out.println("Registering the implementation from " + impl.getClass());
		registration = context.registerService(HelloWorld.class.getName(), impl, null);
	}

	public void stop(BundleContext context) throws Exception {
		if(registration != null) {
			registration.unregister();
			registration = null;
		}
	}

}
