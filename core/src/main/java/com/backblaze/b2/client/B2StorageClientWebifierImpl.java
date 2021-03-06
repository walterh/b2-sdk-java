/*
 * Copyright 2017, Backblaze Inc. All Rights Reserved.
 * License https://www.backblaze.com/using_b2_code.html
 */
package com.backblaze.b2.client;

import com.backblaze.b2.client.contentHandlers.B2ContentSink;
import com.backblaze.b2.client.contentSources.B2ContentSource;
import com.backblaze.b2.client.contentSources.B2Headers;
import com.backblaze.b2.client.contentSources.B2HeadersImpl;
import com.backblaze.b2.client.exceptions.B2Exception;
import com.backblaze.b2.client.exceptions.B2LocalException;
import com.backblaze.b2.client.exceptions.B2UnauthorizedException;
import com.backblaze.b2.client.structures.B2AccountAuthorization;
import com.backblaze.b2.client.structures.B2AuthorizeAccountRequest;
import com.backblaze.b2.client.structures.B2Bucket;
import com.backblaze.b2.client.structures.B2CancelLargeFileRequest;
import com.backblaze.b2.client.structures.B2CancelLargeFileResponse;
import com.backblaze.b2.client.structures.B2CreateBucketRequestReal;
import com.backblaze.b2.client.structures.B2DeleteBucketRequestReal;
import com.backblaze.b2.client.structures.B2DeleteFileVersionRequest;
import com.backblaze.b2.client.structures.B2DeleteFileVersionResponse;
import com.backblaze.b2.client.structures.B2DownloadAuthorization;
import com.backblaze.b2.client.structures.B2DownloadByIdRequest;
import com.backblaze.b2.client.structures.B2DownloadByNameRequest;
import com.backblaze.b2.client.structures.B2FileVersion;
import com.backblaze.b2.client.structures.B2FinishLargeFileRequest;
import com.backblaze.b2.client.structures.B2GetDownloadAuthorizationRequest;
import com.backblaze.b2.client.structures.B2GetFileInfoRequest;
import com.backblaze.b2.client.structures.B2GetUploadPartUrlRequest;
import com.backblaze.b2.client.structures.B2GetUploadUrlRequest;
import com.backblaze.b2.client.structures.B2HideFileRequest;
import com.backblaze.b2.client.structures.B2ListBucketsRequest;
import com.backblaze.b2.client.structures.B2ListBucketsResponse;
import com.backblaze.b2.client.structures.B2ListFileNamesRequest;
import com.backblaze.b2.client.structures.B2ListFileNamesResponse;
import com.backblaze.b2.client.structures.B2ListFileVersionsRequest;
import com.backblaze.b2.client.structures.B2ListFileVersionsResponse;
import com.backblaze.b2.client.structures.B2ListPartsRequest;
import com.backblaze.b2.client.structures.B2ListPartsResponse;
import com.backblaze.b2.client.structures.B2ListUnfinishedLargeFilesRequest;
import com.backblaze.b2.client.structures.B2ListUnfinishedLargeFilesResponse;
import com.backblaze.b2.client.structures.B2Part;
import com.backblaze.b2.client.structures.B2StartLargeFileRequest;
import com.backblaze.b2.client.structures.B2TestMode;
import com.backblaze.b2.client.structures.B2UpdateBucketRequest;
import com.backblaze.b2.client.structures.B2UploadFileRequest;
import com.backblaze.b2.client.structures.B2UploadListener;
import com.backblaze.b2.client.structures.B2UploadPartRequest;
import com.backblaze.b2.client.structures.B2UploadPartUrlResponse;
import com.backblaze.b2.client.structures.B2UploadUrlResponse;
import com.backblaze.b2.client.webApiClients.B2WebApiClient;
import com.backblaze.b2.json.B2Json;
import com.backblaze.b2.util.B2ByteProgressListener;
import com.backblaze.b2.util.B2ByteRange;
import com.backblaze.b2.util.B2InputStreamWithByteProgressListener;
import com.backblaze.b2.util.B2Preconditions;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

import static com.backblaze.b2.util.B2StringUtil.percentEncode;

public class B2StorageClientWebifierImpl implements B2StorageClientWebifier {
    // this is
    private static String API_VERSION_PATH = "b2api/v1/";

    private final B2WebApiClient webApiClient;
    private final String userAgent;
    private final Base64.Encoder base64Encoder = Base64.getEncoder();

    // the masterUrl is a url like "https://api.backblazeb2.com/".
    // this url is only used for authorizeAccount.  after that,
    // the urls from the accountAuthorization or other requests
    // that return a url are used.
    //
    // it always ends with a '/'.
    private final String masterUrl;
    private final B2TestMode testModeOrNull;

    public B2StorageClientWebifierImpl(B2WebApiClient webApiClient,
                                       String userAgent,
                                       String masterUrl,
                                       B2TestMode testModeOrNull) {
        throwIfBadUserAgent(userAgent);
        this.webApiClient = webApiClient;
        this.userAgent = userAgent;
        this.masterUrl = masterUrl.endsWith("/") ?
                masterUrl :
                masterUrl + "/";
        this.testModeOrNull = testModeOrNull;
    }

    String getMasterUrl() {
        return masterUrl;
    }

    // see https://tools.ietf.org/html/rfc7231
    // for now, let's just make sure there aren't any characters that are
    // traditional ascii control characters, including \r and \n since they
    // could mess up our HTTP headers.
    private static void throwIfBadUserAgent(String userAgent) {
        userAgent.chars().forEach( c -> B2Preconditions.checkArgument(c >= 32, "control character in user-agent!"));
    }

    private static class Empty {
        @B2Json.constructor(params = "")
        Empty() {
        }
    }

    @Override
    public void close() {
        webApiClient.close();
    }

    @Override
    public B2AccountAuthorization authorizeAccount(B2AuthorizeAccountRequest request) throws B2Exception {
        final B2HeadersImpl.Builder headersBuilder = B2HeadersImpl
                .builder()
                .set(B2Headers.AUTHORIZATION, makeAuthorizationValue(request));
        setCommonHeaders(headersBuilder);
        final B2Headers headers = headersBuilder.build();

        final String url = masterUrl + API_VERSION_PATH + "b2_authorize_account";
        try {
            return webApiClient.postJsonReturnJson(
                    url,
                    headers,
                    new Empty(), // the arguments are in the header.
                    B2AccountAuthorization.class);
        } catch (B2UnauthorizedException e) {
            e.setRequestCategory(B2UnauthorizedException.RequestCategory.ACCOUNT_AUTHORIZATION);
            throw e;
        }
    }

    private String makeAuthorizationValue(B2AuthorizeAccountRequest request) {
        final String value = request.getAccountId() + ":" + request.getApplicationKey();
        return "Basic " + base64Encoder.encodeToString(value.getBytes());
    }

    @Override
    public B2Bucket createBucket(B2AccountAuthorization accountAuth,
                                 B2CreateBucketRequestReal request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_create_bucket"),
                makeHeaders(accountAuth),
                request,
                B2Bucket.class);
    }


    @Override
    public B2ListBucketsResponse listBuckets(B2AccountAuthorization accountAuth,
                                             B2ListBucketsRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_list_buckets"),
                makeHeaders(accountAuth),
                request,
                B2ListBucketsResponse.class);
    }

    @Override
    public B2UploadUrlResponse getUploadUrl(B2AccountAuthorization accountAuth,
                                            B2GetUploadUrlRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_get_upload_url"),
                makeHeaders(accountAuth),
                request,
                B2UploadUrlResponse.class);

    }

    @Override
    public B2UploadPartUrlResponse getUploadPartUrl(B2AccountAuthorization accountAuth,
                                                B2GetUploadPartUrlRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_get_upload_part_url"),
                makeHeaders(accountAuth),
                request,
                B2UploadPartUrlResponse.class);
    }

    @Override
    public B2FileVersion uploadFile(B2UploadUrlResponse uploadUrlResponse,
                                    B2UploadFileRequest request) throws B2Exception {
        final B2UploadListener uploadListener = request.getListener();
        final B2ContentSource source = request.getContentSource();
        try (final B2ContentDetailsForUpload contentDetails = new B2ContentDetailsForUpload(request.getContentSource())) {
            final long contentLen = contentDetails.getContentLength();

            uploadListener.progress(B2UploadProgressUtil.forSmallFileWaitingToStart(contentLen));
            uploadListener.progress(B2UploadProgressUtil.forSmallFileStarting(contentLen));

            // build the headers.
            final B2HeadersImpl.Builder headersBuilder = B2HeadersImpl
                    .builder()
                    .set(B2Headers.AUTHORIZATION, uploadUrlResponse.getAuthorizationToken())
                    .set(B2Headers.FILE_NAME, percentEncode(request.getFileName()))
                    .set(B2Headers.CONTENT_TYPE, request.getContentType())
                    .set(B2Headers.CONTENT_SHA1, contentDetails.getContentSha1HeaderValue());
            setCommonHeaders(headersBuilder);

            // if the source provides a last-modified time, add it.
            final Long lastModMillis;
            try {
                lastModMillis = source.getSrcLastModifiedMillisOrNull();
            } catch (IOException e) {
                throw new B2LocalException("read_failed", "failed to get lastModified from source: " + e, e);
            }
            if (lastModMillis != null) {
                headersBuilder.set(B2Headers.SRC_LAST_MODIFIED_MILLIS, Long.toString(lastModMillis, 10));
            }

            // add any custom file infos.
            // XXX: really percentEncode the keys?  maybe check for ok characters instead?
            request.getFileInfo().forEach((k, v) -> headersBuilder.set(B2Headers.FILE_INFO_PREFIX + percentEncode(k), percentEncode(v)));

            final B2ByteProgressListener progressAdapter = new B2UploadProgressAdapter(uploadListener, 0, 1, 0, contentLen);
            final B2ByteProgressFilteringListener progressListener = new B2ByteProgressFilteringListener(progressAdapter);

            try {
                final B2FileVersion version = webApiClient.postDataReturnJson(
                        uploadUrlResponse.getUploadUrl(),
                        headersBuilder.build(),
                        new B2InputStreamWithByteProgressListener(contentDetails.getInputStream(), progressListener),
                        contentLen,
                        B2FileVersion.class);
                        //if (System.getenv("FAIL_ME") != null) {
                        //    throw new B2LocalException("test", "failing on purpose!");
                        //}

                uploadListener.progress(B2UploadProgressUtil.forSmallFileSucceeded(contentLen));
                return version;
            } catch (B2UnauthorizedException e) {
                e.setRequestCategory(B2UnauthorizedException.RequestCategory.UPLOADING);
                uploadListener.progress(B2UploadProgressUtil.forSmallFileFailed(contentLen, progressListener.getBytesSoFar()));
                throw e;
            } catch (B2Exception e) {
                uploadListener.progress(B2UploadProgressUtil.forSmallFileFailed(contentLen, progressListener.getBytesSoFar()));
                throw e;
            }
        }
    }

    @Override
    public B2Part uploadPart(B2UploadPartUrlResponse uploadPartUrlResponse,
                             B2UploadPartRequest request) throws B2Exception {
        final B2ContentSource source = request.getContentSource();
        try (final B2ContentDetailsForUpload contentDetails = new B2ContentDetailsForUpload(source)) {

            final B2HeadersImpl.Builder headersBuilder = B2HeadersImpl
                    .builder()
                    .set(B2Headers.AUTHORIZATION, uploadPartUrlResponse.getAuthorizationToken())
                    .set(B2Headers.PART_NUMBER, Integer.toString(request.getPartNumber()))
                    .set(B2Headers.CONTENT_SHA1, contentDetails.getContentSha1HeaderValue());
            setCommonHeaders(headersBuilder);

            try {
                return webApiClient.postDataReturnJson(
                        uploadPartUrlResponse.getUploadUrl(),
                        headersBuilder.build(),
                        contentDetails.getInputStream(),
                        contentDetails.getContentLength(),
                        B2Part.class);
            } catch (B2UnauthorizedException e) {
                e.setRequestCategory(B2UnauthorizedException.RequestCategory.UPLOADING);
                throw e;
            }
        }
    }

    @Override
    public B2ListFileVersionsResponse listFileVersions(B2AccountAuthorization accountAuth,
                                                       B2ListFileVersionsRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_list_file_versions"),
                makeHeaders(accountAuth),
                request,
                B2ListFileVersionsResponse.class);
    }

    @Override
    public B2ListFileNamesResponse listFileNames(B2AccountAuthorization accountAuth,
                                                 B2ListFileNamesRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_list_file_names"),
                makeHeaders(accountAuth),
                request,
                B2ListFileNamesResponse.class);
    }

    @Override
    public B2ListUnfinishedLargeFilesResponse listUnfinishedLargeFiles(B2AccountAuthorization accountAuth,
                                                                       B2ListUnfinishedLargeFilesRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_list_unfinished_large_files"),
                makeHeaders(accountAuth),
                request,
                B2ListUnfinishedLargeFilesResponse.class);
    }

    @Override
    public B2FileVersion startLargeFile(B2AccountAuthorization accountAuth,
                                        B2StartLargeFileRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_start_large_file"),
                makeHeaders(accountAuth),
                request,
                B2FileVersion.class);
    }

    @Override
    public B2FileVersion finishLargeFile(B2AccountAuthorization accountAuth,
                                         B2FinishLargeFileRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_finish_large_file"),
                makeHeaders(accountAuth),
                request,
                B2FileVersion.class);
    }

    @Override
    public B2ListPartsResponse listParts(B2AccountAuthorization accountAuth,
                                         B2ListPartsRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_list_parts"),
                makeHeaders(accountAuth),
                request,
                B2ListPartsResponse.class);
    }

    @Override
    public B2CancelLargeFileResponse cancelLargeFile(
            B2AccountAuthorization accountAuth,
            B2CancelLargeFileRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_cancel_large_file"),
                makeHeaders(accountAuth),
                request,
                B2CancelLargeFileResponse.class);
    }

    @Override
    public void downloadById(B2AccountAuthorization accountAuth,
                             B2DownloadByIdRequest request,
                             B2ContentSink handler) throws B2Exception {
        downloadGuts(accountAuth,
                makeDownloadByIdUrl(accountAuth, request.getFileId(), request.getB2ContentDisposition()),
                request.getRange(),
                handler);
    }

    @Override
    public String getDownloadByIdUrl(B2AccountAuthorization accountAuth,
                              B2DownloadByIdRequest request) throws B2Exception {
        return makeDownloadByIdUrl(accountAuth, request.getFileId(), request.getB2ContentDisposition());
    }

    @Override
    public void downloadByName(B2AccountAuthorization accountAuth,
                               B2DownloadByNameRequest request,
                               B2ContentSink handler) throws B2Exception {
        downloadGuts(accountAuth,
                makeDownloadByNameUrl(accountAuth, request.getBucketName(), request.getFileName(), request.getB2ContentDisposition()),
                request.getRange(),
                handler);
    }

    @Override
    public String getDownloadByNameUrl(B2AccountAuthorization accountAuth,
                                B2DownloadByNameRequest request) throws B2Exception {
        return makeDownloadByNameUrl(accountAuth, request.getBucketName(), request.getFileName(), request.getB2ContentDisposition());
    }

    private void downloadGuts(B2AccountAuthorization accountAuth,
                              String url,
                              B2ByteRange rangeOrNull,
                              B2ContentSink handler) throws B2Exception {
        final Map<String, String> extras = new TreeMap<>();
        if (rangeOrNull != null) {
            extras.put(B2Headers.RANGE, rangeOrNull.toString());
        }
        webApiClient.getContent(
                url,
                makeHeaders(accountAuth, extras),
                handler);
    }

    @Override
    public B2DeleteFileVersionResponse deleteFileVersion(B2AccountAuthorization accountAuth,
                                                         B2DeleteFileVersionRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_delete_file_version"),
                makeHeaders(accountAuth),
                request,
                B2DeleteFileVersionResponse.class);
    }

    @Override
    public B2DownloadAuthorization getDownloadAuthorization(B2AccountAuthorization accountAuth,
                                                            B2GetDownloadAuthorizationRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_get_download_authorization"),
                makeHeaders(accountAuth),
                request,
                B2DownloadAuthorization.class);
    }

    @Override
    public B2FileVersion getFileInfo(B2AccountAuthorization accountAuth,
                                     B2GetFileInfoRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_get_file_info"),
                makeHeaders(accountAuth),
                request,
                B2FileVersion.class);
    }

    @Override
    public B2FileVersion hideFile(B2AccountAuthorization accountAuth,
                                  B2HideFileRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_hide_file"),
                makeHeaders(accountAuth),
                request,
                B2FileVersion.class);
    }

    @Override
    public B2Bucket updateBucket(B2AccountAuthorization accountAuth,
                                 B2UpdateBucketRequest request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_update_bucket"),
                makeHeaders(accountAuth),
                request,
                B2Bucket.class);
    }

    @Override
    public B2Bucket deleteBucket(B2AccountAuthorization accountAuth,
                                 B2DeleteBucketRequestReal request) throws B2Exception {
        return webApiClient.postJsonReturnJson(
                makeUrl(accountAuth, "b2_delete_bucket"),
                makeHeaders(accountAuth),
                request,
                B2Bucket.class);
    }

    private void addAuthHeader(B2HeadersImpl.Builder builder,
                               B2AccountAuthorization accountAuth) {
        builder.set(B2Headers.AUTHORIZATION, accountAuth.getAuthorizationToken());
    }

    private B2Headers makeHeaders(B2AccountAuthorization accountAuth) {
        return makeHeaders(accountAuth, null);
    }

    private B2Headers makeHeaders(B2AccountAuthorization accountAuth, Map<String,String> extrasPairsOrNull) {
        final B2HeadersImpl.Builder builder = B2HeadersImpl
                .builder();
        addAuthHeader(builder, accountAuth);
        if (extrasPairsOrNull != null) {
            extrasPairsOrNull.forEach(builder::set);
        }
        setCommonHeaders(builder);

        return builder.build();
    }

    private void setCommonHeaders(B2HeadersImpl.Builder builder) {
        builder.set(B2Headers.USER_AGENT, userAgent);

        //
        // note that not all test modes affect every request,
        // but let's keep it simple and send with every request.
        //
        if (testModeOrNull != null) {
            builder.set(B2Headers.TEST_MODE, testModeOrNull.getValueForHeader());
        }
    }


    private String makeUrl(B2AccountAuthorization accountAuth,
                           String apiName) {
        String url = accountAuth.getApiUrl();
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += API_VERSION_PATH;
        url += apiName;
        return url;
    }

    private String makeDownloadByIdUrl(B2AccountAuthorization accountAuth,
                                       String fguid,
                                       String b2ContentDisposition) {
        String url = accountAuth.getDownloadUrl();
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += API_VERSION_PATH + "b2_download_file_by_id?fileId=" + fguid;
        url += maybeB2ContentDisposition('&', b2ContentDisposition);
        return url;
    }

    private String makeDownloadByNameUrl(B2AccountAuthorization accountAuth,
                                         String bucketName,
                                         String fileName,
                                         String b2ContentDisposition) {
        String url = accountAuth.getDownloadUrl();
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += "file/" + bucketName + "/" + percentEncode(fileName);
        url += maybeB2ContentDisposition('?', b2ContentDisposition);
        return url;
    }

    /**
     * if b2ContentDisposition isn't null, this will return it in a url query
     * parameter format prefixed with the given separator.  otherwise, it will
     * return an empty string.
     */
    private String maybeB2ContentDisposition(char separator,
                                             String b2ContentDisposition) {
        if (b2ContentDisposition == null) {
            return "";
        } else {
            return separator + "b2ContentDisposition=" + percentEncode(b2ContentDisposition);
        }
    }
}
