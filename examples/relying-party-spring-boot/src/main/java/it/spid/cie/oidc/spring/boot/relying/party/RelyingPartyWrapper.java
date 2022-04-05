package it.spid.cie.oidc.spring.boot.relying.party;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchService;

import javax.annotation.PostConstruct;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import it.spid.cie.oidc.callback.RelyingPartyLogoutCallback;
import it.spid.cie.oidc.config.RelyingPartyOptions;
import it.spid.cie.oidc.exception.OIDCException;
import it.spid.cie.oidc.handler.RelyingPartyHandler;
import it.spid.cie.oidc.schemas.OIDCProfile;
import it.spid.cie.oidc.schemas.WellKnownData;
import it.spid.cie.oidc.spring.boot.relying.party.config.OidcConfig;
import it.spid.cie.oidc.spring.boot.relying.party.persistence.H2PersistenceImpl;
import it.spid.cie.oidc.util.Validator;

@Component
public class RelyingPartyWrapper {

	public String getAuthorizeURL(
			String spidProvider, String trustAnchor, String redirectUri, String scope,
			String profile, String prompt)
		throws OIDCException {

		return relyingPartyHandler.getAuthorizeURL(
			spidProvider, trustAnchor, redirectUri, scope, profile, prompt);
	}

	public JSONObject getUserInfo(String state, String code)
		throws OIDCException {

		return relyingPartyHandler.getUserInfo(state, code);
	}

	public WellKnownData getWellKnownData(String requestURL, boolean jsonMode)
		throws OIDCException {

		return relyingPartyHandler.getWellKnownData(requestURL, jsonMode);
	}

	public WellKnownData getFederationEntityData()
		throws OIDCException {

		return relyingPartyHandler.getWellKnownData(true);
	}

	public String performLogout(String userKey, RelyingPartyLogoutCallback callback)
		throws OIDCException {

		return relyingPartyHandler.performLogout(userKey, callback);
	}

	public void reloadHandler() throws OIDCException {
		logger.info("reload handler");

		postConstruct();
	}

	@PostConstruct
	private void postConstruct() throws OIDCException {
		String jwk = readFile(oidcConfig.getRelyingParty().getJwkFilePath());
		String trustMarks = readFile(
			oidcConfig.getRelyingParty().getTrustMarksFilePath());

		logger.info("final jwk: " + jwk);
		logger.info("final trust_marks: " + trustMarks);

		RelyingPartyOptions options = new RelyingPartyOptions()
			.setDefaultTrustAnchor(oidcConfig.getDefaultTrustAnchor())
			.setSPIDProviders(oidcConfig.getIdentityProviders(OIDCProfile.SPID))
			.setTrustAnchors(oidcConfig.getTrustAnchors())
			.setApplicationName(oidcConfig.getRelyingParty().getApplicationName())
			.setClientId(oidcConfig.getRelyingParty().getClientId())
			.setRedirectUris(oidcConfig.getRelyingParty().getRedirectUris())
			.setContacts(oidcConfig.getRelyingParty().getContacts())
			.setJWK(jwk)
			.setTrustMarks(trustMarks);

		relyingPartyHandler = new RelyingPartyHandler(options, persistenceImpl);
	}

	private String readFile(String filePath) {
		try {
			File file = new File(filePath);

			if (file.isFile() && file.canRead()) {
				return Files.readString(file.toPath());
			}
		}
		catch (Exception e) {
			logger.error(e.getMessage(), e);
		}

		return "";
	}

	private static Logger logger = LoggerFactory.getLogger(RelyingPartyWrapper.class);

	@Autowired
	private OidcConfig oidcConfig;

	@Autowired
	private H2PersistenceImpl persistenceImpl;

	private RelyingPartyHandler relyingPartyHandler;

}
