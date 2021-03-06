package tika.processor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.*;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tika.legacy.ImageMagickConfig;
import tika.legacy.LegacyPdfProcessorConfig;
import tika.legacy.LegacyPdfProcessorParser;
import tika.model.TikaProcessingResult;
import tika.utils.TikaUtils;
import javax.annotation.PostConstruct;


/**
 * A default, composite Tika processor.
 *
 * In contrast to "legacy" processor it uses the default approach implemented in Tika, i.e. when
 * parsing PDF documents, it runs the processing independently per each PDF page,
 * and hence running Tesseract Page-Count times.
 */
@Component("compositeTikaProcessor")
public class CompositeTikaProcessor extends AbstractTikaProcessor {

    @Autowired
    private CompositeTikaProcessorConfig compositeTikaProcessorConfig;

    @Autowired
    private LegacyPdfProcessorConfig legacyPdfProcessorConfig;

    /**
     In order to properly handle PDF documents and OCR we need three separate parsers:
     - a generic parser (for any, non-PDF document type),
     - one that will extract text only from PDFs,
     - one that will apply OCR on PDFs (when stored only images).

     In the default configuration of PDFParser the OCR is disabled when extracting text from PDFs. However, OCR is
     enabled when extracting text from documents of image type. When using default parser with OCR enabled (strategy:
     extract both text and OCR), it will actually always apply OCR on the PDFs even when there is text-only provided.

     We would also like to know when OCR was applied as it will affect the accuracy of the extracted text that will be
     passed to the downstream analysis applications.
     */

    // common tika and parsers configuration
    private TikaConfig tikaConfig;
    private TesseractOCRConfig tessConfig;

    // the default, generic parser for handling all document types (expect PDF)
    private AutoDetectParser defaultParser;
    private ParseContext defaultParseContext;

    // the default parser for PDFs (no OCR)
    private PDFParser pdfTextParser;
    private ParseContext pdfTextParseContext;

    // the parser to extract text from PDFs using OCR
    private PDFParser pdfOcrParser;
    private ParseContext pdfOcrParseContext;

    // the parser to extract text from PDFs using OCR only for single-pages
    // (used to strip-off clutter from LibreOffice-generated PDFs just with images)
    private LegacyPdfProcessorParser pdfSinglePageOcrParser;
    private ParseContext pdfSinglePageOcrParseContext;


    private Logger log = LoggerFactory.getLogger(CompositeTikaProcessor.class);


    @PostConstruct
    @Override
    public void init() throws Exception {

        tikaConfig = new TikaConfig();

        initializeTesseractConfig();

        initializeDefaultParser();

        initializePdfTextOnlyParser();

        initializePdfOcrParser();

        if (compositeTikaProcessorConfig.isUseLegacyOcrParserForSinglePageDocuments()) {
            initializePdfLegacyOcrParser();
        }
    }

    @Override
    public void reset() throws Exception {
        // actually, we only need to re-initialize all the resources apart from the configuration
        init();
    }

    protected TikaProcessingResult processStream(TikaInputStream stream) {
        final int MIN_TEXT_BUFFER_SIZE = 1024;

        TikaProcessingResult result;
        try {
            ByteArrayOutputStream outStream = new ByteArrayOutputStream(MIN_TEXT_BUFFER_SIZE);
            BodyContentHandler handler = new BodyContentHandler(outStream);
            Metadata metadata = new Metadata();

            // mark the stream for multi-pass processing
            if (stream.markSupported()) {
                stream.mark(Integer.MAX_VALUE);
            }

            // try to detect whether the document is PDF
            if (isDocumentOfPdfType(stream)) {

                // firstly try the default parser
                pdfTextParser.parse(stream, handler, metadata, pdfTextParseContext);

                // check if there have been enough characters read / extracted and the we read enough bytes from the stream
                // (images embedded in the documents will occupy quite more space than just raw text)
                if (outStream.size() < compositeTikaProcessorConfig.getPdfMinDocTextLength()
                        && stream.getPosition() > compositeTikaProcessorConfig.getPdfMinDocByteSize()) {

                    // since we are perfoming a second pass over the document, we need to reset cursor position
                    // in both input and output streams
                    stream.reset();
                    outStream.reset();

                    final boolean useOcrLegacyParser = compositeTikaProcessorConfig.isUseLegacyOcrParserForSinglePageDocuments()
                            && TikaUtils.getPageCount(metadata) == 1;


                    // TODO: Q: shall we use a clean metadata or re-use some of the previously parsed fields???
                    handler = new BodyContentHandler(outStream);
                    metadata = new Metadata();

                    if (useOcrLegacyParser) {
                        pdfSinglePageOcrParser.parse(stream, handler, metadata, pdfSinglePageOcrParseContext);

                        // since we use the parser manually, update the metadata with the name of the parser class used
                        metadata.add("X-Parsed-By", LegacyPdfProcessorParser.class.getName());
                    }
                    else {
                        pdfOcrParser.parse(stream, handler, metadata, pdfOcrParseContext);

                        // since we use the parser manually, update the metadata with the name of the parser class used
                        metadata.add("X-Parsed-By", PDFParser.class.getName());
                    }
                }
                else {
                    // since we use the parser manually, update the metadata with the name of the parser class used
                    metadata.add("X-Parsed-By", PDFParser.class.getName());
                }
            }
            else {
                // otherwise, run default documents parser
                defaultParser.parse(stream, handler, metadata, defaultParseContext);
            }

            // parse the metadata and store the result
            Map<String, Object> resultMeta = TikaUtils.extractMetadata(metadata);

            result = TikaProcessingResult.builder()
                    .text(outStream.toString())
                    .metadata(resultMeta)
                    .success(true)
                    .timestamp(OffsetDateTime.now())
                    .build();
        }
        catch (Exception e) {
            log.error(e.getMessage());

            result = TikaProcessingResult.builder()
                    .error("Exception caught while processing the document: " + e.getMessage())
                    .success(false)
                    .build();
        }

        return result;
    }


    private boolean isDocumentOfPdfType(InputStream stream) throws Exception {
        Metadata metadata = new Metadata();
        MediaType mediaType = defaultParser.getDetector().detect(stream, metadata);

        return mediaType.equals(MediaType.application("pdf"));
    }


    private void initializeTesseractConfig() {
        tessConfig = new TesseractOCRConfig();

        tessConfig.setTimeout(compositeTikaProcessorConfig.getOcrTimeout());
        tessConfig.setApplyRotation(compositeTikaProcessorConfig.isOcrApplyRotation());
        if (compositeTikaProcessorConfig.isOcrEnableImageProcessing()) {
            tessConfig.setEnableImageProcessing(1);
        }
        else {
            tessConfig.setEnableImageProcessing(0);
        }
        tessConfig.setLanguage(compositeTikaProcessorConfig.getOcrLanguage());
    }


    private void initializeDefaultParser() {
        defaultParser = new AutoDetectParser(tikaConfig);

        defaultParseContext = new ParseContext();
        defaultParseContext.set(TikaConfig.class, tikaConfig);
        defaultParseContext.set(TesseractOCRConfig.class, tessConfig);
        defaultParseContext.set(Parser.class, defaultParser); //need to add this to make sure recursive parsing happens!
    }


    private void initializePdfTextOnlyParser() {
        PDFParserConfig pdfTextOnlyConfig = new PDFParserConfig();
        pdfTextOnlyConfig.setExtractInlineImages(false);
        pdfTextOnlyConfig.setExtractUniqueInlineImagesOnly(false); // do not extract multiple inline images
        pdfTextOnlyConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);

        pdfTextParser = new PDFParser();
        pdfTextParseContext = new ParseContext();
        pdfTextParseContext.set(TikaConfig.class, tikaConfig);
        pdfTextParseContext.set(PDFParserConfig.class, pdfTextOnlyConfig);
        //pdfTextParseContext.set(Parser.class, defaultParser); //need to add this to make sure recursive parsing happens!
    }


    private void initializePdfOcrParser() {
        PDFParserConfig pdfOcrConfig = new PDFParserConfig();
        pdfOcrConfig.setExtractUniqueInlineImagesOnly(false); // do not extract multiple inline images
        if (compositeTikaProcessorConfig.isPdfOcrOnlyStrategy()) {
            pdfOcrConfig.setExtractInlineImages(false);
            pdfOcrConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.OCR_ONLY);
        }
        else {
            pdfOcrConfig.setExtractInlineImages(true);
            // warn: note that applying 'OCR_AND_TEXT_EXTRACTION' the content can be duplicated
            pdfOcrConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.OCR_AND_TEXT_EXTRACTION);
        }

        pdfOcrParser = new PDFParser();
        pdfOcrParseContext = new ParseContext();
        pdfOcrParseContext.set(TikaConfig.class, tikaConfig);
        pdfOcrParseContext.set(PDFParserConfig.class, pdfOcrConfig);
        pdfOcrParseContext.set(TesseractOCRConfig.class, tessConfig);
        //pdfOcrParseContext.set(Parser.class, defaultParser); //need to add this to make sure recursive parsing happens!
    }

    private void initializePdfLegacyOcrParser() {
        pdfSinglePageOcrParser = new LegacyPdfProcessorParser();

        pdfSinglePageOcrParseContext = new ParseContext();
        pdfSinglePageOcrParseContext.set(TikaConfig.class, tikaConfig);
        pdfSinglePageOcrParseContext.set(LegacyPdfProcessorConfig.class, legacyPdfProcessorConfig);

        TesseractOCRConfig tessConfig = new TesseractOCRConfig();
        tessConfig.setTimeout(legacyPdfProcessorConfig.getOcrTimeout());
        pdfSinglePageOcrParseContext.set(TesseractOCRConfig.class, tessConfig);

        ImageMagickConfig imgConfig = new ImageMagickConfig();
        imgConfig.setTimeout(legacyPdfProcessorConfig.getConversionTimeout());
        pdfSinglePageOcrParseContext.set(ImageMagickConfig.class, imgConfig);

        //pdfOcrParseContext.set(Parser.class, defaultParser); //need to add this to make sure recursive parsing happens!
    }
}
