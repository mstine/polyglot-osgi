package eg;

public class Driver {

	public static void main(String[] args) throws Exception {
		System.out.println("Starting...");
		OSGiRuntime osgi = null;
		try {
			osgi = new OSGiRuntime();
			ServiceProvider servicesFactory = osgi.trackService("eg.api.HelloWorld");

			for(String arg : args) {
				System.out.println("Processing " + arg);
				osgi.loadBundleFile(arg);
			}

			for(Object impl : servicesFactory.getServices()) {
				impl.getClass().getMethod("greet").invoke(impl);
			}
			
		} finally {
			if(osgi != null) osgi.stop();
		}
		System.out.println("Done!");
	}

}
