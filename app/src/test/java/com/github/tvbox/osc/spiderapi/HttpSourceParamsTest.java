package com.github.tvbox.osc.spiderapi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Map;

/** HttpSourceParams(type0/1/4 HTTP 源请求参数构造)等值单测:VM 旧路径与 typed 共用同一语义 */
public class HttpSourceParamsTest {

    @Test
    public void detail_type0_usesVideolistAc() {
        Map<String, String> p = HttpSourceParams.detail(0, "1001");
        assertEquals("videolist", p.get("ac"));
        assertEquals("1001", p.get("ids"));
        assertEquals(2, p.size());
    }

    @Test
    public void detail_type1_and_type4_useDetailAc() {
        assertEquals("detail", HttpSourceParams.detail(1, "1001").get("ac"));
        assertEquals("detail", HttpSourceParams.detail(4, "1001").get("ac"));
    }

    @Test
    public void detail_unsupportedTypeOrNullIdReturnsNull() {
        assertNull(HttpSourceParams.detail(3, "1001"));
        assertNull(HttpSourceParams.detail(2, "1001"));
        assertNull(HttpSourceParams.detail(0, null));
    }
}
