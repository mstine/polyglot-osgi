package eg;

import eg.api.HelloWorld;

public class Driver {

	public static void main(String[] args) throws Exception {
		System.out.println("Starting...");
		OSGiRuntime osgi = null;
		try {
			osgi = new OSGiRuntime("eg.api", "eg.osgi.helpers");
			ServiceProvider<HelloWorld> servicesFactory = osgi.trackService(HelloWorld.class);

			for(String arg : args) {
				System.out.println("Processing " + arg);
				osgi.loadBundleFile(arg);
			}

			for(HelloWorld impl : servicesFactory.getServices()) {
				impl.greet();
			}
			
		} finally {
			if(osgi != null) osgi.stop();
		}
		System.out.println("Done!");
	}

}
