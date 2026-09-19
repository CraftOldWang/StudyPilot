package com.studyagent;

import com.studyagent.config.AiModelProperties;
import com.studyagent.config.CanalProperties;
import com.studyagent.config.ElasticsearchProperties;
import com.studyagent.config.ObjectStorageProperties;
import com.studyagent.config.RagProperties;
import com.studyagent.config.StudyRocketMqProperties;
import com.studyagent.config.UploadProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * StudyAgent Spring Boot 启动类。
 */
@SpringBootApplication
@MapperScan("com.studyagent.mapper")
@EnableConfigurationProperties({
        AiModelProperties.class,
        ObjectStorageProperties.class,
        ElasticsearchProperties.class,
        RagProperties.class,
        StudyRocketMqProperties.class,
        UploadProperties.class,
        com.studyagent.config.AnkiProperties.class,
        com.studyagent.config.AsrProperties.class,
        com.studyagent.config.LearningMemoryProperties.class,
        com.studyagent.config.DemoProperties.class,
        CanalProperties.class
})
public class StudyAgentApplication {

    public static void main(String[] args) throws java.io.IOException {
        // This Windows host cannot connect AF_UNIX sockets created in its system TEMP directory.
        if (System.getProperty("os.name").startsWith("Windows") && System.getProperty("jdk.net.unixdomain.tmpdir") == null) {
            var sockets = java.nio.file.Files.createDirectories(java.nio.file.Path.of(".eval", "sockets")).toAbsolutePath();
            System.setProperty("jdk.net.unixdomain.tmpdir", sockets.toString());
        }
        SpringApplication.run(StudyAgentApplication.class, args);
    }
}
