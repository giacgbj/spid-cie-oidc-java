package it.spid.cie.oidc.exception;

public class EntityException extends OIDCException {

	public static class Generic extends EntityException {

		public Generic(String message) {
			super(message);
		}

		public Generic(Throwable cause) {
			super(cause);
		}

	}

	public static class MissingJwksClaim extends EntityException {

		public MissingJwksClaim(String message) {
			super(message);
		}

		public MissingJwksClaim(Throwable cause) {
			super(cause);
		}

	}

	private EntityException(String message) {
		super(message);
	}

	private EntityException(Throwable cause) {
		super(cause);
	}

	private static final long serialVersionUID = 9206740073587833396L;

}
