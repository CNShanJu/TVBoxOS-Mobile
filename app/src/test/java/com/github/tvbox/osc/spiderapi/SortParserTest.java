package com.github.tvbox.osc.spiderapi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.AbsSortXml;

import org.junit.Test;

/** SortParser(首页/分类 JSON 解析+筛选)单测 */
public class SortParserTest {

    @Test
    public void parseSortJson_withFilters() {
        String json = "{\"class\":[{\"type_id\":\"1\",\"type_name\":\"电影\",\"type_flag\":\"1\"}],"
                + "\"filters\":{\"1\":[{\"key\":\"area\",\"name\":\"地区\","
                + "\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"内地\",\"v\":\"内地\"}]}]}}";
        AbsSortXml xml = SortParser.parseSortJson(json);
        assertNotNull(xml);
        assertNotNull(xml.classes);
        assertEquals(1, xml.classes.sortList.size());
        assertEquals("1", xml.classes.sortList.get(0).id);
        assertNotNull(xml.classes.sortList.get(0).filters);
        assertEquals(1, xml.classes.sortList.get(0).filters.size());
        assertEquals("area", xml.classes.sortList.get(0).filters.get(0).key);
        assertEquals(2, xml.classes.sortList.get(0).filters.get(0).values.size());
        assertTrue(xml.classes.sortList.get(0).filters.get(0).values.containsKey("内地"));
    }

    @Test
    public void parseSortJson_invalid_returnsNull() {
        assertNull(SortParser.parseSortJson("not-json"));
        assertNull(SortParser.parseSortJson(null));
    }
}
