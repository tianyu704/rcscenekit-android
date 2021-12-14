package cn.rongcloud.corekit.core;

import android.content.Context;

import org.apache.commons.codec.digest.DigestUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.zeroturnaround.zip.ZipUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import cn.rongcloud.corekit.annotation.KitBean;
import cn.rongcloud.corekit.api.IRCSceneKitEngine;
import cn.rongcloud.corekit.bean.KitInfo;
import cn.rongcloud.corekit.net.oklib.OkApi;
import cn.rongcloud.corekit.net.oklib.WrapperCallBack;
import cn.rongcloud.corekit.net.oklib.api.callback.FileIOCallBack;
import cn.rongcloud.corekit.net.oklib.wrapper.Wrapper;
import cn.rongcloud.corekit.utils.FileUtils;
import cn.rongcloud.corekit.utils.GsonUtil;
import cn.rongcloud.corekit.utils.HandlerUtils;
import cn.rongcloud.corekit.utils.ListUtil;
import cn.rongcloud.corekit.utils.VMLog;
import okhttp3.Response;

/**
 * Created by gyn on 2021/11/15
 */
public class RCSceneKitEngineImpl implements IRCSceneKitEngine {

    private final static String TAG = RCSceneKitEngineImpl.class.getSimpleName();

    private final static Holder holder = new Holder();
    private final List<RCKitInit> coreKitInitList = new ArrayList<>();
    private Context mContext;
    private final Map<String, Object> configMap = new HashMap<>();
    private String mAppKey;
    private JSONObject configObject = null;

    public static IRCSceneKitEngine getInstance() {
        return holder.instance;
    }

    /**
     * 注册Kit模块
     *
     * @param kit 要注册的kit
     */
    @Override
    public void installKit(RCKitInit... kit) {
        if (kit == null) {
            return;
        }
        for (RCKitInit kitInit : kit) {
            if (!coreKitInitList.contains(kitInit)) {
                coreKitInitList.add(kitInit);
            }
        }
    }

    /**
     * 初始化SDK
     *
     * @param context 上下文
     * @param appKey  appKey
     */
    @Override
    public void initWithAppKey(Context context, String appKey) {
        mContext = context.getApplicationContext();
        mAppKey = appKey;
        initSubKit();
        loadJson();
        createRootFolder();
        loadAllKitInfo();
    }

    /**
     * 创建根目录文件夹
     */
    public void createRootFolder() {
        File file = new File(CoreKitConstant.getRootPath());
        if (!file.exists()) {
            file.mkdirs();
        }
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    /**
     * 获取对应Kit的UI参数
     *
     * @param c   kit对应的数据类
     * @param <T> 泛型
     * @return 返回kit对应的UI数据
     */
    @Override
    public <T> T getKitConfig(Class<T> c) {
        KitBean kitBean = (KitBean) c.getAnnotation(KitBean.class);
        if (kitBean == null) {
            VMLog.e(TAG, "Please add @KitBean annotation in your class");
            return null;
        }

        String parseKey = kitBean.parseKey();

        if (configMap.containsKey(parseKey)) {
            return (T) configMap.get(parseKey);
        }

        if (null == configObject) {
            VMLog.e(TAG, "Please initWithAppKey at your application");
            return null;
        }

        JSONObject object = parseObject(configObject, parseKey);
        if (null == object) {
            VMLog.d(TAG, "Config file is not contains filed " + parseKey);
            return null;
        }

        T t = GsonUtil.json2Obj(object.toString(), c);
        configMap.put(parseKey, t);

        return t;
    }

    /**
     * 加载config文件或从网络下载
     */
    private void loadJson() {
        // TODO 加载网络配置
        // 加载默认配置
        String configJson = FileUtils.getStringFromAssets(mContext, "KitConfig.json");
        try {
            configObject = new JSONObject(configJson);
        } catch (JSONException e) {
            VMLog.e(TAG, e.getLocalizedMessage());
        }
    }

    private JSONObject parseObject(JSONObject object, String key) {
        if (object.has(key)) {
            return object.optJSONObject(key);
        }
        Iterator<String> keys = object.keys();
        String k;
        JSONObject o;
        while (keys.hasNext()) {
            k = keys.next();
            o = object.optJSONObject(k);
            if (o != null) {
                return parseObject(o, key);
            }
        }
        return null;
    }

    /**
     * 初始化子模块Kit
     */
    private void initSubKit() {
        for (RCKitInit k : coreKitInitList) {
            k.init(mContext);
        }
    }

    /**
     * 下载所有kit的版本信息
     */
    private void loadAllKitInfo() {
        OkApi.get(CoreKitConstant.Api.KIT_INFO_LIST, null, new WrapperCallBack() {
            @Override
            public void onResult(Wrapper result) {
                VMLog.d(TAG, "kit info load success");
                List<KitInfo> kitInfoList = result.getList(KitInfo.class);
                if (ListUtil.isNotEmpty(kitInfoList)) {
                    VMLog.d(TAG, "your kit size :" + kitInfoList.size());
                    for (KitInfo kitInfo : kitInfoList) {
                        checkKitNeedUpdate(kitInfo);
                    }
                }
            }

            @Override
            public void onError(int code, String msg) {
                super.onError(code, msg);
                VMLog.e(TAG, "kit info load failed :" + code + "->" + msg);
            }
        });
    }

    /**
     * 检查kit是否需要更新
     *
     * @param kitInfo
     */
    private void checkKitNeedUpdate(KitInfo kitInfo) {
        if (kitInfo == null) {
            return;
        }
        String path = CoreKitConstant.getKitInfoPath(kitInfo.getName());
        File file = new File(path);
        if (file.exists()) {
            String json = FileUtils.getStringFromFile(file);
            KitInfo localKitInfo = GsonUtil.json2Obj(json, KitInfo.class);
            if (!kitInfo.equals(localKitInfo)) {
                loadKitZip(kitInfo);
            }
        } else {
            loadKitZip(kitInfo);
        }
    }

    /**
     * 下载压缩包
     *
     * @param kitInfo
     */
    private void loadKitZip(KitInfo kitInfo) {
        if (kitInfo == null) {
            return;
        }
        String tempKitName = kitInfo.getKitId() + ".zip";
        OkApi.download(kitInfo.getUrl(), null, new FileIOCallBack(CoreKitConstant.getTempPath(), tempKitName) {
            @Override
            public File onParse(Response response) throws IOException {
                return super.onParse(response);
            }

            @Override
            public File saveFile(Response response) throws IOException {
                File file = super.saveFile(response);
                VMLog.d(TAG, "kit zip load success");
                // 对文件取MD5，对比完整性
                String md5 = DigestUtils.md5Hex(new FileInputStream(file));
                if (kitInfo.isValidate(md5)) {
                    // kit存储目录
                    File kitFile = new File(CoreKitConstant.getKitPath(kitInfo.getName(), kitInfo.getKitId()));
                    // 删除原有目录中的文件
                    if (kitFile.exists() && kitFile.isDirectory()) {
                        FileUtils.delAllFile(kitFile.getAbsolutePath());
                    } else {
                        kitFile.mkdirs();
                    }
                    // 解压缩zip到指定目录
                    ZipUtil.unpack(file, kitFile);
                    // 更新kitInfo信息
                    updateKitInfo(kitInfo);
                    // 刷新每个kit的kitConfig
                    refreshKitConfig();
                }
                return file;
            }

            @Override
            public void onResult(File result) {
                super.onResult(result);
                // 删除zip文件
                if (result != null && result.exists()) {
                    result.delete();
                }
            }

            @Override
            public void onError(int code, String msg) {
                super.onError(code, msg);
                VMLog.e(TAG, "kit zip load failed :" + code + "->" + msg);
            }
        });
    }

    /**
     * 更新KitInfo信息
     *
     * @param kitInfo
     */
    private void updateKitInfo(KitInfo kitInfo) {
        FileUtils.writeString(CoreKitConstant.getKitInfoPath(kitInfo.getName()), GsonUtil.obj2Json(kitInfo));
    }

    /**
     * 刷新每个kit的配置信息
     */
    private void refreshKitConfig() {
        HandlerUtils.mainThreadPost(new Runnable() {
            @Override
            public void run() {
                for (RCKitInit kitInit : coreKitInitList) {
                    kitInit.refreshKitConfig();
                }
            }
        });
    }

    private static class Holder {
        private final IRCSceneKitEngine instance = new RCSceneKitEngineImpl();
    }

}
