package com.github.tvbox.osc.spiderapi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;

import org.junit.Test;

import java.util.List;

/** AbsXmlParser 纯解析契约测试(type1 JSON 样例;type0 XML 依赖 XStream 注解,样例由真机源覆盖) */
public class AbsXmlParserTest {

    private static final String SAMPLE_JSON =
            "{\"code\":1,\"list\":[{" +
                    "\"vod_id\":\"1\",\"vod_name\":\"测试剧\"," +
                    "\"vod_play_from\":\"快看\"," +
                    "\"vod_play_url\":\"第1集$http://example.com/1.mp4#第2集$http://example.com/2.mp4\"" +
                    "}]}";

    @Test
    public void parseJson_returnsMovieAndSetsSourceKey() throws Exception {
        AbsXml data = AbsXmlParser.parseJson(SAMPLE_JSON, "src-key");
        assertNotNull(data);
        assertNotNull(data.movie);
        assertNotNull(data.movie.videoList);
        assertTrue(!data.movie.videoList.isEmpty());
        Movie.Video v = data.movie.videoList.get(0);
        assertEquals("src-key", v.sourceKey);
    }

    @Test
    public void parseJson_parsesPlayUrlToBeanList() throws Exception {
        AbsXml data = AbsXmlParser.parseJson(SAMPLE_JSON, "src-key");
        Movie.Video v = data.movie.videoList.get(0);
        if (v.urlBean != null && v.urlBean.infoList != null && !v.urlBean.infoList.isEmpty()) {
            List<Movie.Video.UrlBean.UrlInfo.InfoBean> beanList =
                    v.urlBean.infoList.get(0).beanList;
            assertNotNull(beanList);
            assertTrue(!beanList.isEmpty());
            // 第一条线路 "第1集$http..."
            assertEquals("第1集", beanList.get(0).name);
        }
    }

    @Test
    public void parseJson_garbageThrows() {
        assertThrows(Exception.class, () -> AbsXmlParser.parseJson("{not-json", "src"));
    }
}
