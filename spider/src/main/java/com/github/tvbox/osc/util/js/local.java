package com.github.tvbox.osc.util.js;

import androidx.annotation.Keep;

import com.github.tvbox.osc.config.KeyValueStore;
import com.whl.quickjs.wrapper.Function;

public class local {@Keep@Function
    public void delete(String str, String str2) {
        try {
            KeyValueStore.delete("jsRuntime_" + str + "_" + str2);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }@Keep@Function
    public String get(String str, String str2) {
        try {
            return KeyValueStore.get("jsRuntime_" + str + "_" + str2, "");
        } catch (Exception e) {
            KeyValueStore.delete(str);
            return str2;
        }
    }@Keep@Function
    public void set(String str, String str2, String str3) {
        try {
            KeyValueStore.put("jsRuntime_" + str + "_" + str2, str3);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}