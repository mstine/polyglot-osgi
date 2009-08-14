package eg;

public class Driver {

	public static void main(String[] args) {
		OSGiRuntime osgi = null;
		try {
			osgi = new OSGiRuntime();
		} finally {
			if(osgi != null) osgi.stop();
		}
	}

}
