package com.precheck.parse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 只做一件事：把 PDF 的文本层读成字符串。
 *
 * <p>它不认识发票，也不做任何判断——这样将来要支持图片 OCR，
 * 只要再写一个实现类，上面的解析逻辑完全不用改。
 *
 * <p><b>重要限制</b>：只能读"电子发票"（税务局生成的 PDF，内嵌了文字）。
 * 拍照或扫描出来的 PDF 里只有图片、没有文字层，抽出来是空字符串。
 * 那种情况属于 OCR 的范畴，不在本项目范围内——所以这个方法返回空串时，
 * 上层必须如实告诉用户"这份材料读不出来，请手工填写"，而不是当成 0 元。
 */
@Component
public class PdfTextExtractor {

    public String extract(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.isEncrypted()) {
                throw new IOException("PDF 已加密，无法读取内容");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }
}
