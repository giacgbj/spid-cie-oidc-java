package it.spid.cie.oidc.model;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.spid.cie.oidc.exception.EntityException;
import it.spid.cie.oidc.exception.JWTException;
import it.spid.cie.oidc.exception.OIDCException;
import it.spid.cie.oidc.exception.TrustChainException;
import it.spid.cie.oidc.helper.EntityHelper;
import it.spid.cie.oidc.helper.JWTHelper;
import it.spid.cie.oidc.util.ListUtil;
import it.spid.cie.oidc.util.StringUtil;
import it.spid.cie.oidc.util.Validator;

public class EntityConfiguration {

	public static EntityConfiguration of(CachedEntityInfo entityInfo, JWTHelper jwtHelper)
		throws OIDCException {

		return new EntityConfiguration(entityInfo.getJwt(), jwtHelper);
	}

	/**
	 *
	 * @param jwt the JWS Token
	 */
	public EntityConfiguration(String jwt, JWTHelper jwtHelper) throws OIDCException {
		this(jwt, null, jwtHelper);
	}

	public EntityConfiguration(
			String jwt, EntityConfiguration trustAnchor, JWTHelper jwtHelper)
		throws OIDCException {

		this.jwt = jwt;
		this.jwtHelper = jwtHelper;
		this.trustAnchor = trustAnchor;

		JSONObject token = JWTHelper.fastParse(jwt);

		this.header = token.getJSONObject("header");
		this.payload = token.getJSONObject("payload");

		if (logger.isDebugEnabled()) {
			logger.debug("fastParse=" + token.toString());
		}

		this.sub = payload.getString("sub");
		this.iss = payload.getString("iss");
		this.exp = payload.getLong("exp");

		extractJwks();
	}

	public void addFailedDescendantStatement(String key, JSONObject value) {
		this.failedDescendantStatements.put(key, value);
	}

	public void addVerifiedDescendantStatement(String key, JSONObject value) {
		this.verifiedDescendantStatements.put(key, value);
	}

	public int getConstraint(String key, int defaultValue) {
		JSONObject json = payload.optJSONObject("constraints");

		if (json != null) {
			return json.optInt(key, defaultValue);
		}

		return defaultValue;
	}

	public long getExp() {
		return exp;
	}

	public LocalDateTime getExpiresOn() {
		return LocalDateTime.ofEpochSecond(exp, 0, ZoneOffset.UTC);
	}

	public String getFederationFetchEndpoint() {
		JSONObject metadata = payload.optJSONObject("metadata");

		if (metadata != null) {
			JSONObject federationEntity = metadata.optJSONObject(
				"federation_entity");

			if (federationEntity != null) {
				return federationEntity.optString("federation_fetch_endpoint");
			}
		}

		return null;
	}

	public LocalDateTime getIssuedAt() {
		return LocalDateTime.ofEpochSecond(iat, 0, ZoneOffset.UTC);
	}

	public String getJwt() {
		return jwt;
	}

	public String getPayload() {
		return payload.toString();
	}

	public JSONObject getPayloadMetadata() {
		return payload.optJSONObject("metadata", new JSONObject());
	}

	public String getSubject() {
		return sub;
	}

	/**
	 * Get superiors entity configurations
	 *
	 * @param maxAuthorityHints
	 * @param superiorHints
	 * @return
	 * @throws OIDCException
	 */
	public Map<String, EntityConfiguration> getSuperiors(
			int maxAuthorityHints, List<EntityConfiguration> superiorHints)
		throws OIDCException {

		List<String> authorityHints = getPayloadStringArray("authority_hints");

		// Apply limits on hints per hop if defined

		if (maxAuthorityHints > 0 && authorityHints.size() > maxAuthorityHints) {
			int end = authorityHints.size() - maxAuthorityHints;

			logger.warn(
				"Found {} but authority maximum hints is set to {}. The following " +
				"authorities will be ignored: {}", authorityHints.size(),
				maxAuthorityHints, StringUtil.merge(
					ListUtil.subList(authorityHints, 0, end)));

			authorityHints = ListUtil.lasts(authorityHints, maxAuthorityHints);
		}

		for (EntityConfiguration sup : superiorHints) {
			if (authorityHints.contains(sup.getSubject())) {
				logger.info(
					"Getting Cached Entity Configurations for {}", sup.getSubject());

				authorityHints.remove(sup.getSubject());
				verifiedSuperiors.put(sup.getSubject(), sup);
			}
		}

		logger.debug(
			"Getting Entity Configurations for {}", StringUtil.merge(authorityHints));

		for (String authorityHint : authorityHints) {
			EntityConfiguration ec;

			try {
				String jwt = EntityHelper.getEntityConfiguration(
					authorityHint);

				ec = new EntityConfiguration(jwt, jwtHelper);
			}
			catch (Exception e) {
				logger.warn("Get Entity Configuration for {}: {}", jwt, e);

				continue;
			}

			if (ec.validateItself()) {
				this.verifiedSuperiors.put(ec.getSubject(), ec);
			}
			else {
				this.failedSuperiors.put(ec.getSubject(), ec);
			}
		}

		// TODO: Python code logs failed.

		return this.verifiedSuperiors;
	}

	public List<EntityConfiguration> getVerifiedBySuperiors() {
		List<EntityConfiguration> result = new ArrayList<>(
			this.verifiedBySuperiors.values());

		return Collections.unmodifiableList(result);
	}

	public JSONObject getVerifiedDescendantPayloadMetadataPolicy(String metadataType) {
		// TODO: What if we have more than one entry?
		Iterator<JSONObject> itr = this.verifiedDescendantStatements.values().iterator();

		if (!itr.hasNext()) {
			return null;
		}

		JSONObject value = itr.next();

		return value.optJSONObject(
				"metadata_policy", new JSONObject()
			).optJSONObject(metadataType);
	}

	public List<String> getVerifiedDescendantStatement() {
		List<String> result = new ArrayList<>();

		for (JSONObject value : verifiedDescendantStatements.values()) {
			result.add(value.toString());
		}

		return Collections.unmodifiableList(result);
	}

	/**
	 * @param key
	 * @return {@code true} if the {@code constraints} section inside {@code payload} has
	 * an element named {@code key}
	 */
	public boolean hasConstraint(String key) {
		JSONObject json = payload.optJSONObject("constraints");

		if (json != null) {
			return json.has(key);
		}

		return false;
	}

	public boolean hasVerifiedBySuperiors() {
		return !verifiedBySuperiors.isEmpty();
	}

	public boolean hasVerifiedDescendantStatement() {
		return !verifiedDescendantStatements.isEmpty();
	}

	public boolean isValid() {
		return valid;
	}

	public void setAllowedTrustMarks(String[] allowedTrustMarks) {
		this.allowedTrustMarks = Arrays.asList(allowedTrustMarks);
	}

	/**
	 * Validate the entity configuration only if marked by a well known trust mark, issued
	 * by a trusted issuer
	 *
	 * @return
	 * @throws OIDCException
	 */
	public boolean validateByAllowedTrustMarks() throws OIDCException {
		if (trustAnchor == null) {
			throw new TrustChainException.TrustAnchorNeeded(
				"To validate the trust marks the Trust Anchor Entity Configuration " +
				"is needed.");
		}

		if (allowedTrustMarks.isEmpty()) {
			return true;
		}

		JSONArray trustMarks = payload.optJSONArray("trust_marks");

		if (trustMarks == null) {
			logger.warn(
				"{} doesn't have the trust marks claim in its Entity Configuration",
				this.sub);

			return false;
		}

		// TODO: Implement TrustMark checks
		logger.error("TODO: Implement TrustMark checks");

		return true;
	}

	/**
	 * Validate this EntityConfiguration with the jwks contained in the statement of the
	 * superior
	 *
	 * @param jwt the statement issued by the superior
	 * @param ec the superior entity configuration
	 * @return
	 * @throws OIDCException
	 */
	public boolean validateBySuperior(String jwt, EntityConfiguration ec)
		throws OIDCException {

		boolean valid = false;

		JSONObject payload = null;

		try {
			payload = JWTHelper.fastParsePayload(jwt);

			if (ec.validateItself(false)) {
				if (ec.validateDescendant(jwt)) {

					// Validate entity JWS using superior JWKSet

					JWKSet jwkSet = JWTHelper.getJWKSetFromJWT(jwt);

					valid = jwtHelper.verifyJWS(this.jwt, jwkSet);
				}
			}
		}
		catch (Exception e) {
			StringBuilder sb = new StringBuilder();

			sb.append(getSubject());
			sb.append(" failed validation with ");
			sb.append(ec.getSubject());
			sb.append("'s superior statement ");

			if (payload != null) {
				sb.append(payload.toString());
			}
			else {
				sb.append(jwt);
			}

			sb.append(". Exception ");
			sb.append(e);

			logger.warn(sb.toString());
		}

		if (valid) {
			ec.addVerifiedDescendantStatement(getSubject(), payload);

			this.verifiedBySuperiors.put(payload.getString("iss"), ec);
			this.valid = true;
		}
		else {
			ec.addFailedDescendantStatement(getSubject(), payload);
		}

		return valid;
	}
	/**
	 * Validates this entity configuration with the entity statements issued by
	 * its superiors.
	 * <br/>
	 * This method fills the following internal properties:
	 * <ul>
	 *   <li>verifiedSuperiors</li>
	 *   <li>failedSuperiors</li>
	 *   <li>verifiedBySuperiors</li>
	 *   <li>failedBySuperiors</li>
	 *  </ul>
	 *
	 * @param superiors
	 * @return the verifiedSuperiors property
	 * @throws Exception
	 */
	public Map<String, EntityConfiguration> validateBySuperiors(
			Collection<EntityConfiguration> superiors)
		throws Exception {

		for (EntityConfiguration ec : superiors) {
			if (this.verifiedBySuperiors.containsKey(ec.getSubject())) {
				continue;
			}

			String federationApiEndpoint = ec.getFederationFetchEndpoint();

			if (Validator.isNullOrEmpty(federationApiEndpoint)) {
				logger.warn(
					"Missing federation_fetch_endpoint in federation_entity " +
					"metadata for {} by {}", getSubject(), ec.getSubject());

				this.invalidSuperiors.put(ec.getSubject(), null);

				continue;
			}

			String url = federationApiEndpoint + "?sub=" + getSubject();

			logger.info("Getting entity statements from {}", url);

			String jwt = EntityHelper.getEntityStatement(url);

			validateBySuperior(jwt, ec);
		}

		return Collections.unmodifiableMap(this.verifiedBySuperiors);
	}

	/**
	 *
	 * @param jwt a descendant entity statement issued by this
	 * @return
	 * @throws Exception
	 */
	public boolean validateDescendant(String jwt) throws OIDCException {

		// Fast decode JWT token

		JSONObject token = JWTHelper.fastParse(jwt);

		JSONObject header = token.getJSONObject("header");

		if (logger.isDebugEnabled()) {
			logger.debug("validateDescendant " + token.toString());
		}

		// Check kid coherence

		String kid = header.optString("kid");

		if (!this.jwksKids.contains(kid)) {
			throw new JWTException.UnknownKid(kid, jwkSet.toString());
		}

		if (jwtHelper.verifyJWS(jwt, this.jwkSet)) {
			return true;
		}
		else {
			// TODO: have to throw exception?
			return false;
		}
	}

	/**
	 * Validate the EntityConfiguration by itself
	 */
	public boolean validateItself() {
		try {
			return validateItself(true);
		}
		catch (Exception e) {
			// Ignore
		}

		return false;
	}

	/**
	 * Validate the EntityConfiguration by itself
	 *
	 * @param silentMode when {@code false} Exceptions will be propagated to caller
	 * @return {@code true} if entity jwt is self validated
	 * @throws Exception
	 */
	public boolean validateItself(boolean silentMode) throws OIDCException {
		try {
			this.valid = jwtHelper.verifyJWS(this.jwt, this.jwkSet);

			return this.valid;
		}
		catch (OIDCException e) {
			logger.error(e.getMessage(), e);

			if (!silentMode) {
				throw e;
			}
		}

		return false;
	}

	protected List<String> getPayloadStringArray(String key) {
		List<String> result = new ArrayList<>();

		JSONArray array = payload.optJSONArray(key);

		if (array != null) {
			for (int x = 0; x < array.length(); x++) {
				result.add(array.getString(x));
			}
		}

		return result;
	}

	private void extractJwks() throws OIDCException {
		JSONObject jwks = payload.optJSONObject("jwks");

		if (jwks != null) {
			this.jwkSet = JWTHelper.getJWKSetFromJSON(jwks);
		}

		if (jwkSet == null || jwkSet.getKeys().size() == 0) {
			String msg = String.format("Missing jwks in the statement for {}", sub);

			logger.error(msg);

			throw new EntityException.MissingJwksClaim(msg);
		}

		for (JWK key : jwkSet.getKeys()) {
			jwksKids.add(key.getKeyID());
		}
	}

	private static final Logger logger = LoggerFactory.getLogger(
		EntityConfiguration.class);

	private final String jwt;
	private final JWTHelper jwtHelper;
	private EntityConfiguration trustAnchor;
	private JSONObject header;
	private JSONObject payload;
	private String sub;
	private String iss;
	private long exp;
	private long iat;
	private JWKSet jwkSet;
	private List<String> jwksKids = new ArrayList<>();
	private Map<String, EntityConfiguration> failedBySuperiors = new HashMap<>();
	private Map<String, EntityConfiguration> verifiedBySuperiors = new HashMap<>();
	private Map<String, EntityConfiguration> failedSuperiors = new HashMap<>();
	private Map<String, EntityConfiguration> invalidSuperiors = new HashMap<>();
	private Map<String, EntityConfiguration> verifiedSuperiors = new HashMap<>();
	private Map<String, JSONObject> failedDescendantStatements = new HashMap<>();
	private Map<String, JSONObject> verifiedDescendantStatements = new HashMap<>();
	private List<String> allowedTrustMarks = new ArrayList<>();

	private boolean valid = false;

}
