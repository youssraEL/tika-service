package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import service.controller.TikaServiceConfig;
import service.model.ServiceInformation;
import service.model.ServiceResponseContent;
import tika.DocumentProcessorTests;
import tika.DocumentTestUtils;
import tika.legacy.LegacyPdfProcessorConfig;
import tika.model.TikaProcessingResult;
import tika.processor.CompositeTikaProcessorConfig;

import java.io.InputStream;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest(classes = TikaServiceApplication.class)
@RunWith(SpringRunner.class)
@AutoConfigureMockMvc
@ContextConfiguration(classes = {TikaServiceConfig.class, LegacyPdfProcessorConfig.class, CompositeTikaProcessorConfig.class})
@TestPropertySource(properties = {"spring.config.location = classpath:tika/config/tika-processor-config.yaml,classpath:application.properties"})
public class ServiceControllerDocumentMultipartFileTests extends DocumentProcessorTests  {

	@Autowired
	private MockMvc mockMvc;

	final private String PROCESS_FILE_ENDPOINT_URL = "/api/process_file";


    @Override
    public void testExtractPdfEx1Encrypted() throws Exception {
        final String docPath = "pdf/ex1_enc.pdf";

        TikaProcessingResult result = sendMultipartFileProcessingRequest(docPath, HttpStatus.BAD_REQUEST);

        // extraction from encrypted PDF will fail with the proper error message
        assertFalse(result.getSuccess());
        assertTrue(result.getError().contains("document is encrypted"));
    }


    @Override
    protected TikaProcessingResult processDocument(final String docPath) throws Exception  {
        return sendMultipartFileProcessingRequest(docPath, HttpStatus.OK);
    }

    private TikaProcessingResult sendMultipartFileProcessingRequest(final String docPath, HttpStatus expectedStatus) throws Exception  {
        InputStream stream = utils.getDocumentStream(docPath);
        MockMultipartFile multipartFile = new MockMultipartFile("file", docPath, "multipart/form-data", stream);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.multipart(PROCESS_FILE_ENDPOINT_URL)
                .file(multipartFile))
                //.param("some-random", "4"))
                .andExpect(status().is(expectedStatus.value()))
                .andReturn();
        //.andExpect(content().string("success"));

        assertEquals(expectedStatus.value(), result.getResponse().getStatus());
        assertNotNull(result.getResponse().getContentAsString());

        // parse content
        ObjectMapper mapper = new ObjectMapper();
        TikaProcessingResult tikaResult = mapper.readValue(result.getResponse().getContentAsString(),
                ServiceResponseContent.class).getResult();

        return tikaResult;
    }
}