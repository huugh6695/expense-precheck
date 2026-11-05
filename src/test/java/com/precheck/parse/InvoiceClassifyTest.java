package com.precheck.parse;

import com.precheck.config.PrecheckRuleProperties;
import com.precheck.facts.Facts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 票据类型判断的测试。
 *
 * <p>R2（附件是否齐全）全靠这个判断，判错了会给出很荒唐的建议
 * （比如把机票认成住宿票，然后说"缺住宿发票"），所以单独测。
 */
class InvoiceClassifyTest {

    private final InvoiceParser parser = new InvoiceParser(
            new PdfTextExtractor(), new InvoiceTextParser(), new PrecheckRuleProperties());

    private ParsedInvoiceFields fields(String item, String seller, String invoiceNo) {
        return new ParsedInvoiceFields(invoiceNo, null, null, seller, null, item, null);
    }

    @Test
    @DisplayName("机票：项目含运输服务，开票方是航空公司")
    void shouldClassifyTravel() {
        assertEquals(Facts.Attachment.KIND_TRAVEL,
                parser.classify(fields("*运输服务*客运服务费", "东方航空股份有限公司", "NO001")));
    }

    @Test
    @DisplayName("打车：开票方是出行平台也算交通")
    void shouldClassifyTaxiAsTravel() {
        assertEquals(Facts.Attachment.KIND_TRAVEL,
                parser.classify(fields("*运输服务*客运服务费", "滴滴出行科技有限公司", "NO002")));
    }

    @Test
    @DisplayName("住宿：项目或开票方含酒店/住宿")
    void shouldClassifyHotel() {
        assertEquals(Facts.Attachment.KIND_HOTEL,
                parser.classify(fields(null, "杭州西溪某某酒店", "NO003")));
        assertEquals(Facts.Attachment.KIND_HOTEL,
                parser.classify(fields("*住宿服务*住宿费", null, "NO004")));
    }

    @Test
    @DisplayName("普通商品票：既不是交通也不是住宿，算一般发票")
    void shouldClassifyPlainInvoice() {
        assertEquals(Facts.Attachment.KIND_INVOICE,
                parser.classify(fields("*鞋*运动鞋", "厦门天邻缘电子商务有限公司", "NO005")));
    }

    @Test
    @DisplayName("既认不出类别又没有发票号：标记为未识别，而不是硬塞一个类型")
    void shouldClassifyUnknown() {
        assertEquals(Facts.Attachment.KIND_OTHER, parser.classify(fields(null, null, null)));
    }
}
