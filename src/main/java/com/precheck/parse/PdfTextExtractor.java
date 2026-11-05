package com.precheck.parse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 只做一件事：把 PDF 的文本层按页读成字符串列表。
 *
 * <p>它不认识发票，也不做任何判断——这样将来要支持图片 OCR，
 * 只要再写一个实现类，上面的解析逻辑完全不用改。
 *
 * <p><b>为什么必须按页读：</b>财务经常把一整个月的发票打印成一个 PDF，
 * 一次报销下来二十几页。整份读成一个字符串，就只能认出第一张，
 * 其余全部丢失——那不是"精度问题"，是"材料白传了"。
 *
 * <p><b>重要限制</b>：只能读"电子发票"（税务局生成的 PDF，内嵌了文字）。
 * 拍照或扫描出来的 PDF 里只有图片、没有文字层，抽出来是空字符串。
 * 那种情况属于 OCR 的范畴，不在本项目范围内——所以返回空串时，
 * 上层必须如实告诉用户"这份材料读不出来，请手工填写"，而不是当成 0 元。
 */
@Component
public class PdfTextExtractor {

    /**
     * 一次最多解析多少页。
     *
     * <p>不是技术限制，是防止有人传一个 800 页的扫描册把服务拖住——
     * 那种文件本来也不该走这条通道。
     */
    public static final int MAX_PAGES = 100;

    /**
     * 按页抽文本。
     *
     * @return 每页一段文本（可能为空串，表示这页没有文字层），外加文档总页数
     * @throws IOException 文件读不开、或已加密
     */
    public PdfPages extractPages(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.isEncrypted()) {
                throw new IOException("PDF 已加密，无法读取内容");
            }
            int total = doc.getNumberOfPages();
            int toRead = Math.min(total, MAX_PAGES);

            List<String> pages = new ArrayList<>(toRead);
            for (int page = 1; page <= toRead; page++) {
                // 每页单独取一次：PDFTextStripper 的 start/endPage 是实例状态，
                // 复用同一个实例容易踩到内部缓存，官方示例也是每页新建
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(doc));
            }
            return new PdfPages(pages, total);
        }
    }

    /**
     * 抽取结果。
     *
     * @param pages      实际抽出来的每页文本（最多 {@link #MAX_PAGES} 页）
     * @param totalPages 文档真实总页数。比 pages.size() 大，就说明被截断了，上层要告诉用户
     */
    public record PdfPages(List<String> pages, int totalPages) {

        public PdfPages {
            pages = (pages == null) ? List.of() : List.copyOf(pages);
        }

        public boolean truncated() {
            return totalPages > pages.size();
        }
    }
}
