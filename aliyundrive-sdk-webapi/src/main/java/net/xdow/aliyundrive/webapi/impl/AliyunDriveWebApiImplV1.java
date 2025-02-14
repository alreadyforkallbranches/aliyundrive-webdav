package net.xdow.aliyundrive.webapi.impl;


import net.xdow.aliyundrive.IAliyunDrive;
import net.xdow.aliyundrive.IAliyunDriveAuthorizer;
import net.xdow.aliyundrive.bean.*;
import net.xdow.aliyundrive.net.AccessTokenInvalidInterceptor;
import net.xdow.aliyundrive.net.AliyunDriveCall;
import net.xdow.aliyundrive.net.XHttpLoggingInterceptor;
import net.xdow.aliyundrive.util.JsonUtils;
import net.xdow.aliyundrive.util.StringUtils;
import net.xdow.aliyundrive.webapi.AliyunDriveWebConstant;
import net.xdow.aliyundrive.webapi.bean.AliyunDriveWebRequest;
import net.xdow.aliyundrive.webapi.bean.AliyunDriveWebResponse;
import net.xdow.aliyundrive.webapi.bean.AliyunDriveWebShareRequestInfo;
import net.xdow.aliyundrive.webapi.net.AliyunDriveWebCall;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class AliyunDriveWebApiImplV1 implements IAliyunDrive {
    private static final Logger LOGGER = LoggerFactory.getLogger(AliyunDriveWebApiImplV1.class);

    private OkHttpClient mOkHttpClient;
    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private AliyunDriveResponse.AccessTokenInfo mAccessTokenInfo;
    private IAliyunDriveAuthorizer mAliyunDriveAuthorizer;
    private AccessTokenInvalidInterceptor mAccessTokenInvalidInterceptor = new AccessTokenInvalidInterceptor();

    public AliyunDriveWebApiImplV1() {
        initOkHttp();
    }

    private void initOkHttp() {
        XHttpLoggingInterceptor loggingInterceptor = new XHttpLoggingInterceptor();
        this.mOkHttpClient = new OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor) //response ↑
                .addInterceptor(this.mAccessTokenInvalidInterceptor) //response ↑
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request request = chain.request();
                        Response response = chain.proceed(AliyunDriveWebApiImplV1.this.buildCommonRequestHeader(request));
                        int code = response.code();
                        if (code == 400 || code == 401) {
                            ResponseBody body = response.peekBody(40960);
                            String res = body.string();
                            String url = request.url().toString();
                            boolean isRenewRequest = url.endsWith("/renew_session");
                            if (!isRenewRequest && res.contains("DeviceSessionSignatureInvalid")) {
                                AliyunDriveWebApiImplV1.this.onAuthorizerEvent(AliyunDriveWebConstant.Event.DEVICE_SESSION_SIGNATURE_INVALID);
                                return chain.proceed(AliyunDriveWebApiImplV1.this.buildCommonRequestHeader(request));
                            } else if (!isRenewRequest && res.contains("UserDeviceOffline")) {
                                AliyunDriveWebApiImplV1.this.onAuthorizerEvent(AliyunDriveWebConstant.Event.USER_DEVICE_OFFLINE);
                                LOGGER.error("登录设备过多, 请进入\"登录设备管理\", 退出一些设备。");
                                if (!url.endsWith("/token/refresh")) {
                                    AliyunDriveWebApiImplV1.this.requestNewAccessToken();
                                    Response retryResponse = chain.proceed(AliyunDriveWebApiImplV1.this.buildCommonRequestHeader(request));
                                    ResponseBody retryBody = response.peekBody(40960);
                                    String retryRes = retryBody.string();
                                    if (retryRes.contains("UserDeviceOffline")) {
                                        LOGGER.error("重新登录失败, 设备数过多, 等待30分钟...");
                                        //防止请求数过多
                                        try {
                                            TimeUnit.MINUTES.sleep(30);
                                        } catch (InterruptedException e) {
                                        }
                                    }
                                }
                                return response;
                            }
                        }
                        return response;
                    }
                }) //response ↑
                .authenticator(new Authenticator() {
                    @Override
                    public Request authenticate(Route route, Response response) throws IOException {
                        int code = response.code();
                        if (code == 401 || code == 400) {
                            ResponseBody body = response.peekBody(40960);
                            String res = body.string();
                            if (res.contains("AccessToken")) {
                                requestNewAccessToken();
                                String accessToken = AliyunDriveWebApiImplV1.this.mAccessTokenInfo.getAccessToken();
                                return response.request().newBuilder()
                                        .removeHeader("authorization")
                                        .header("authorization", accessToken)
                                        .build();
                            }
                        }
                        return null;
                    }
                })
                .readTimeout(1, TimeUnit.MINUTES)
                .writeTimeout(1, TimeUnit.MINUTES)
                .connectTimeout(1, TimeUnit.MINUTES)
                .build();
    }


    public String post(String url, Object body) {
        String bodyAsJson = JsonUtils.toJson(body);
        Request.Builder requestBuilder = new Request.Builder()
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyAsJson))
                .url(url);
        Request request = requestBuilder.build();
        String res = "";
        try {
            Response response = this.mOkHttpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                try {
                    res = response.body().string();
                } catch (Exception e) {
                }
                if (res.contains("refresh_token is not valid")) {
                    onAuthorizerEvent(AliyunDriveWebConstant.Event.REFRESH_TOKEN_INVALID);
                }
                LOGGER.error("请求失败 post {}, body {}, code {} res {}", url, bodyAsJson, response.code(), res);
                return res;
            }
            res = response.body().string();
            LOGGER.info("post {}, body {}, code {} res {}", url, bodyAsJson, response.code(), res);
            return res;
        } catch (Exception e) {
            LOGGER.error("post fail", e);
        }
        return "{ \"code\": \"NetworkError\", \"message\":\"Network error.\"}";
    }

    public void setAccessTokenInfo(AliyunDriveResponse.AccessTokenInfo info) {
        this.mAccessTokenInfo = info;
    }

    @Override
    public void setAuthorizer(IAliyunDriveAuthorizer authorizer) {
        this.mAliyunDriveAuthorizer = authorizer;
    }

    @Override
    public void setAccessTokenInvalidListener(Runnable listener) {
        this.mAccessTokenInvalidInterceptor.setAccessTokenInvalidListener(listener);
    }

    private void onAuthorizerEvent(String eventId) {
        onAuthorizerEvent(eventId, null, null);
    }

    private <T> T onAuthorizerEvent(String eventId, Class<T> resultCls) {
        return (T) onAuthorizerEvent(eventId, null, resultCls);
    }

    private <T> T onAuthorizerEvent(String eventId, Object data, Class<T> resultCls) {
        IAliyunDriveAuthorizer authorizer = this.mAliyunDriveAuthorizer;
        if (authorizer == null) {
            return null;
        }
        return (T) authorizer.onAuthorizerEvent(eventId, data, resultCls);
    }

    private void requestNewAccessToken() {
        IAliyunDriveAuthorizer authorizer = this.mAliyunDriveAuthorizer;
        if (authorizer == null) {
            return;
        }
        try {
            AliyunDriveResponse.AccessTokenInfo newAccessTokenInfo = authorizer.acquireNewAccessToken(this.mAccessTokenInfo);
            if (newAccessTokenInfo != null) {
                this.mAccessTokenInfo = newAccessTokenInfo;
            }
        } catch (Throwable t) {
            System.out.println(t);
        }
    }

    private Request buildCommonRequestHeader(Request request) {
        Request.Builder builder = request.newBuilder();
        Map<String, String> map = getCommonHeaders();
        Iterator<Map.Entry<String, String>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> entry = it.next();
            String key = entry.getKey();
            String value = entry.getValue();
            builder.removeHeader(key);
            builder.addHeader(key, value);
        }
        return builder.build();
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.AccessTokenInfo> getAccessToken(String url) {
        return new AliyunDriveWebCall<>(this.mAccessTokenInfo);
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.QrCodeGenerateInfo> qrCodeGenerate(String url) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.QrCodeQueryStatusInfo> qrCodeQueryStatus(String sid) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String qrCodeImageUrl(String sid) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.AccessTokenInfo> getAccessToken(AliyunDriveRequest.AccessTokenInfo query) {
        if (query.getGrantType() != AliyunDriveEnum.GrantType.RefreshToken) {
            throw new UnsupportedOperationException("getAccessToken grant_type must be refresh_token, got: " + query.getGrantType().name());
        }
        Map<String, String> params = Collections.singletonMap("refresh_token", query.getRefreshToken());
        return postApiRequest(AliyunDriveWebConstant.API_ACCESS_TOKEN, params, AliyunDriveResponse.AccessTokenInfo.class)
                .mockResultOnSuccess(new AliyunDriveWebCall.MockResultCallback<AliyunDriveResponse.AccessTokenInfo>() {
                    @Override
                    public AliyunDriveResponse.AccessTokenInfo onSuccess(AliyunDriveResponse.AccessTokenInfo res) {
                        if (StringUtils.isEmpty(res.getTokenType())) {
                            res.setTokenType("Bearer");
                        }
                        if (StringUtils.isEmpty(res.getExpiresIn())) {
                            res.setExpiresIn("7200");
                        }
                        return res;
                    }
                });
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.QrCodeGenerateInfo> qrCodeGenerate(AliyunDriveRequest.QrCodeGenerateInfo query) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.FileListInfo> fileList(AliyunDriveRequest.FileListInfo query) {
        return postApiRequest(AliyunDriveWebConstant.API_FILE_LIST, query, AliyunDriveResponse.FileListInfo.class);
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.UserSpaceInfo> getUserSpaceInfo() {
        return postApiRequest(AliyunDriveWebConstant.API_USER_GET_SPACE_INFO, AliyunDriveWebResponse.UserSpaceInfo.class);
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.UserDriveInfo> getUserDriveInfo() {
        return postApiRequest(AliyunDriveWebConstant.API_USER_GET_DRIVE_INFO, AliyunDriveResponse.UserDriveInfo.class);
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.FileGetInfo> fileGet(AliyunDriveRequest.FileGetInfo query) {
        return postApiRequest(AliyunDriveWebConstant.API_FILE_GET, query, AliyunDriveResponse.FileGetInfo.class);
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.FileBatchGetInfo> fileBatchGet(AliyunDriveRequest.FileBatchGetInfo query) {
        List<AliyunDriveRequest.FileBatchGetInfo.FileInfo> fileList = query.getFileList();
        List<AliyunDriveFileInfo> items = new ArrayList<>();
        for (AliyunDriveRequest.FileBatchGetInfo.FileInfo fileInfo : fileList) {
            AliyunDriveRequest.FileGetInfo fileGetQuery = new AliyunDriveRequest.FileGetInfo(
                    fileInfo.getDriveId(), fileInfo.getFileId()
            );
            AliyunDriveResponse.FileGetInfo fileGetRes = fileGet(fileGetQuery).execute();
            items.add(fileGetRes);
        }
        AliyunDriveResponse.FileBatchGetInfo res = new AliyunDriveResponse.FileBatchGetInfo();
        res.setItems(items);
        return new AliyunDriveWebCall<>(res);
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.FileGetDownloadUrlInfo> fileGetDownloadUrl(AliyunDriveRequest.FileGetDownloadUrlInfo query) {
        int expireSec = query.getExpireSec();
        if (expireSec < 900 || expireSec > 115200) {
            throw new IllegalArgumentException("Error: expire_sec argument must between 900-115200s, got: " + expireSec);
        }
        return postApiRequest(AliyunDriveWebConstant.API_FILE_GET_DOWNLOAD_URL, query, AliyunDriveResponse.FileGetDownloadUrlInfo.class);
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.FileCreateInfo> fileCreate(AliyunDriveRequest.FileCreateInfo query) {
        List<AliyunDriveFilePartInfo> partInfoList = query.getPartInfoList();
        if (partInfoList != null) {
            int partInfoListSize = partInfoList.size();
            if (partInfoListSize > AliyunDriveWebConstant.MAX_FILE_CREATE_PART_INFO_LIST_SIZE) {
                throw new IllegalArgumentException("Error: max part_info_list size must < "
                        + AliyunDriveWebConstant.MAX_FILE_CREATE_PART_INFO_LIST_SIZE + ", got: " + partInfoListSize);
            }
        }
        return postApiRequest(AliyunDriveWebConstant.API_FILE_CREATE, query, AliyunDriveResponse.FileCreateInfo.class);
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.FileGetUploadUrlInfo> fileGetUploadUrl(AliyunDriveRequest.FileGetUploadUrlInfo query) {
        return postApiRequest(AliyunDriveWebConstant.API_FILE_GET_UPLOAD_URL, query, AliyunDriveResponse.FileGetUploadUrlInfo.class);
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.FileListUploadPartsInfo> fileListUploadedParts(AliyunDriveRequest.FileListUploadPartsInfo query) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.FileUploadCompleteInfo> fileUploadComplete(AliyunDriveRequest.FileUploadCompleteInfo query) {
        return postApiRequest(AliyunDriveWebConstant.API_FILE_UPLOAD_COMPLETE, query, AliyunDriveResponse.FileUploadCompleteInfo.class);
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.FileRenameInfo> fileRename(AliyunDriveRequest.FileRenameInfo query) {
        //拒绝重名
        AliyunDriveRequest.FileCreateInfo createQuery = new AliyunDriveRequest.FileCreateInfo(
                query.getDriveId(), query.getParentFileId(), query.getName(), AliyunDriveEnum.Type.File, AliyunDriveEnum.CheckNameMode.Refuse
        );
        AliyunDriveResponse.FileCreateInfo createRes = this.fileCreate(createQuery).execute();
        String createdFileId = createRes.getFileId();
        if (!StringUtils.isEmpty(createdFileId)) {
            AliyunDriveRequest.FileDeleteInfo deleteQuery = new AliyunDriveRequest.FileDeleteInfo(
                    query.getDriveId(), createdFileId
            );
            this.fileDelete(deleteQuery).execute();
        }
        return postApiRequest(AliyunDriveWebConstant.API_FILE_RENAME, query, AliyunDriveResponse.FileRenameInfo.class);
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.FileMoveInfo> fileMove(AliyunDriveRequest.FileMoveInfo query) {
        return postApiRequest(AliyunDriveWebConstant.API_FILE_MOVE, query, AliyunDriveResponse.FileMoveInfo.class);
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.FileCopyInfo> fileCopy(AliyunDriveRequest.FileCopyInfo query) {
        return postApiRequest(AliyunDriveWebConstant.API_FILE_COPY, query, AliyunDriveResponse.FileCopyInfo.class);
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.FileMoveToTrashInfo> fileMoveToTrash(final AliyunDriveRequest.FileMoveToTrashInfo query) {
        return postApiRequest(AliyunDriveWebConstant.API_FILE_MOVE_TO_TRASH, query, AliyunDriveResponse.FileMoveToTrashInfo.class)
                .mockResultOnSuccess(new AliyunDriveWebCall.MockResultCallback<AliyunDriveResponse.FileMoveToTrashInfo>() {
                    @Override
                    public AliyunDriveResponse.FileMoveToTrashInfo onSuccess(AliyunDriveResponse.FileMoveToTrashInfo res) {
                        if (res == null) {
                            res = new AliyunDriveResponse.FileMoveToTrashInfo();
                            res.setFileId(query.getFileId());
                            res.setDriveId(query.getDriveId());
                            res.setAsync_task_id("");
                        }
                        return res;
                    }
                });
    }

    @Override
    public AliyunDriveCall<AliyunDriveResponse.FileDeleteInfo> fileDelete(final AliyunDriveRequest.FileDeleteInfo query) {
        return postApiRequest(AliyunDriveWebConstant.API_FILE_DELETE, query, AliyunDriveResponse.FileDeleteInfo.class)
                .mockResultOnSuccess(new AliyunDriveWebCall.MockResultCallback<AliyunDriveResponse.FileDeleteInfo>() {
                    @Override
                    public AliyunDriveResponse.FileDeleteInfo onSuccess(AliyunDriveResponse.FileDeleteInfo res) {
                        if (res == null) {
                            res = new AliyunDriveResponse.FileDeleteInfo();
                            res.setFileId(query.getFileId());
                            res.setDriveId(query.getDriveId());
                            res.setAsync_task_id("");
                        }
                        return res;
                    }
                });
    }

    @Override
    public Call upload(String url, byte[] bytes, final int offset, final int byteCount) {
        Request request = new Request.Builder()
                .addHeader(XHttpLoggingInterceptor.SKIP_HEADER_NAME, XHttpLoggingInterceptor.SKIP_HEADER_VALUE)
                .put(RequestBody.create(MediaType.parse(""), bytes, offset, byteCount))
                .url(url).build();
        return this.mOkHttpClient.newCall(request);
    }

    @Override
    public Call download(String url, String range, String ifRange) {
        Request.Builder builder = new Request.Builder();
        builder.addHeader(XHttpLoggingInterceptor.SKIP_HEADER_NAME, XHttpLoggingInterceptor.SKIP_HEADER_VALUE);
        if (range != null) {
            builder.header("range", range);
        }
        if (ifRange != null) {
            builder.header("if-range", ifRange);
        }

        Request request = builder.url(url).build();
        return this.mOkHttpClient.newCall(request);
    }

    @Override
    public Map<String, String> getCommonHeaders() {
        Map<String, String> map = new HashMap<>();
        String deviceId = onAuthorizerEvent(AliyunDriveWebConstant.Event.ACQUIRE_DEVICE_ID, String.class);
        String signature = onAuthorizerEvent(AliyunDriveWebConstant.Event.ACQUIRE_SESSION_SIGNATURE, String.class);

        map.put("User-Agent", AliyunDriveWebConstant.USER_AGENT);
        map.put("x-device-id", deviceId);
        map.put("x-signature", signature + "01");
        map.put("x-canary", "client=web,app=adrive,version=v3.17.0");
        map.put("x-request-id", UUID.randomUUID().toString());
        AliyunDriveResponse.AccessTokenInfo info = this.mAccessTokenInfo;
        if (info != null) {
            map.put("authorization", info.getTokenType() + " " + info.getAccessToken());
        }
        map.put("referer", AliyunDriveWebConstant.REFERER);
        return map;
    }

    public AliyunDriveCall<AliyunDriveWebResponse.ShareTokenInfo> shareToken(AliyunDriveWebRequest.ShareTokenInfo query) {
        return postApiRequest(AliyunDriveWebConstant.API_SHARE_TOKEN, query, AliyunDriveWebResponse.ShareTokenInfo.class);
    }

    public AliyunDriveCall<AliyunDriveResponse.FileListInfo> shareList(AliyunDriveWebRequest.ShareListInfo query) {
        return postApiRequest(AliyunDriveWebConstant.API_SHARE_LIST, query, AliyunDriveResponse.FileListInfo.class);
    }

    public AliyunDriveCall<AliyunDriveWebResponse.ShareSaveInfo> shareSave(AliyunDriveWebRequest.ShareSaveInfo query) {
        return postApiRequest(AliyunDriveWebConstant.API_SHARE_SAVE, query, AliyunDriveWebResponse.ShareSaveInfo.class);
    }

    public AliyunDriveCall<AliyunDriveFileInfo> shareGetFile(AliyunDriveWebRequest.ShareGetFileInfo query) {
        return postApiRequest(AliyunDriveWebConstant.API_SHARE_GET_FILE, query, AliyunDriveFileInfo.class);
    }

    private <T extends AliyunDriveResponse.GenericMessageInfo> AliyunDriveWebCall<T> postApiRequest(
            String url, Class<? extends AliyunDriveResponse.GenericMessageInfo> classOfT) {
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, "{}"))
                .build();
        return new AliyunDriveWebCall<>(mOkHttpClient.newCall(request), classOfT);
    }

    private <T extends AliyunDriveResponse.GenericMessageInfo> AliyunDriveWebCall<T> postApiRequest(
            String url, Object object, Class<T> classOfT) {
        Request.Builder builder = new Request.Builder();
        if (object instanceof AliyunDriveWebShareRequestInfo) {
            String shareToken = ((AliyunDriveWebShareRequestInfo) object).getShareToken();
            if (StringUtils.isEmpty(shareToken)) {
                throw new IllegalArgumentException("share_token is required");
            }
            builder.addHeader("X-Share-Token", shareToken);
        }
        Request request = builder
                .url(url)
                .post(RequestBody.create(JSON, JsonUtils.toJson(object)))
                .build();
        return new AliyunDriveWebCall<>(mOkHttpClient.newCall(request), classOfT);
    }
}
