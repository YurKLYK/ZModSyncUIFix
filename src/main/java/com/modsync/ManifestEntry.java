package com.modsync;

public class ManifestEntry {
    private CategoryType category;
    private String relativePath;
    private String fileName;
    private long fileSize;
    private String sha256;
    private String sha1;
    private boolean required;
    private boolean restartRequired;
    private String downloadUrl;
    /** When true, client skips hash/size check and only verifies the file exists by path. */
    private boolean matchByNameOnly;

    public ManifestEntry() {
    }

    public ManifestEntry(CategoryType category, String relativePath, String fileName, long fileSize,
                         String sha256, boolean required, boolean restartRequired, String downloadUrl) {
        this(category, relativePath, fileName, fileSize, sha256, required, restartRequired, downloadUrl, "");
    }

    public ManifestEntry(CategoryType category, String relativePath, String fileName, long fileSize,
                         String sha256, boolean required, boolean restartRequired, String downloadUrl, String sha1) {
        this.category = category;
        this.relativePath = relativePath;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.sha256 = sha256;
        this.sha1 = sha1;
        this.required = required;
        this.restartRequired = restartRequired;
        this.downloadUrl = downloadUrl;
    }

    public CategoryType getCategory() {
        return category;
    }

    public void setCategory(CategoryType category) {
        this.category = category;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getSha1() {
        return sha1;
    }

    public void setSha1(String sha1) {
        this.sha1 = sha1;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isRestartRequired() {
        return restartRequired;
    }

    public void setRestartRequired(boolean restartRequired) {
        this.restartRequired = restartRequired;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public boolean isMatchByNameOnly() {
        return matchByNameOnly;
    }

    public void setMatchByNameOnly(boolean matchByNameOnly) {
        this.matchByNameOnly = matchByNameOnly;
    }

    public String getIdentityKey() {
        return category.name() + ":" + relativePath;
    }

    public ManifestEntry copy() {
        return new ManifestEntry(category, relativePath, fileName, fileSize, sha256, required, restartRequired, downloadUrl, sha1);
    }
}
