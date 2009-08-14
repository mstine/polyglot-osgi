package eg;

public class Driver {

	public static void main(String[] args) throws Exception {
		OSGiRuntime osgi = null;
		try {
			osgi = new OSGiRuntime();
		} finally {
			if(osgi != null) osgi.stop();
		}
		System.out.println("Done!");
	}

}
