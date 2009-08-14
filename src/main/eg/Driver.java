package eg;

public class Driver {

	public static void main(String[] args) throws Exception {
		System.out.println("Starting...");
		OSGiRuntime osgi = null;
		try {
			osgi = new OSGiRuntime();
			ServiceProvider<HelloWorld> servicesFactory = osgi.trackService(HelloWorld.class);

			for(String arg : args) {
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
