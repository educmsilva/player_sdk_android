package com.sambatech.sample.model;

import com.sambatech.player.model.SambaMediaRequest;

/**
 * @author Leandro Zanol - 16/10/17
 */
public final class MediaInfo {

	private String projectHash;
	private String id;
	private String title;
	private SambaMediaRequest.Environment environment;
	private String description;
	private String thumbnail;
	private String qualifier;
	private EntitlementScheme entitlementScheme;

	// injected or hard-coded properties
	private String url;
	private String type;
	private String streamUrl;
	private String[] backupUrls;
	private String adUrl;
	private boolean autoPlay;

	public String getProjectHash() {
		return projectHash;
	}

	public void setProjectHash(String projectHash) {
		this.projectHash = projectHash;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public SambaMediaRequest.Environment getEnvironment() {
		return environment;
	}

	public void setEnvironment(SambaMediaRequest.Environment environment) {
		this.environment = environment;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getThumbnail() {
		return thumbnail;
	}

	public void setThumbnail(String thumbnail) {
		this.thumbnail = thumbnail;
	}

	public String getQualifier() {
		return qualifier;
	}

	public void setQualifier(String qualifier) {
		this.qualifier = qualifier;
	}

	public void setEntitlementScheme(EntitlementScheme entitlementScheme) {
		this.entitlementScheme = entitlementScheme;
	}

	public EntitlementScheme getEntitlementScheme() {
		return entitlementScheme;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getStreamUrl() {
		return streamUrl;
	}

	public void setStreamUrl(String streamUrl) {
		this.streamUrl = streamUrl;
	}

	public String[] getBackupUrls() {
		return backupUrls;
	}

	public void setBackupUrls(String[] backupUrls) {
		this.backupUrls = backupUrls;
	}

	public String getAdUrl() {
		return adUrl;
	}

	public void setAdUrl(String adUrl) {
		this.adUrl = adUrl;
	}

	public boolean isAutoPlay() {
		return autoPlay;
	}

	public void setAutoPlay(boolean autoPlay) {
		this.autoPlay = autoPlay;
	}
}