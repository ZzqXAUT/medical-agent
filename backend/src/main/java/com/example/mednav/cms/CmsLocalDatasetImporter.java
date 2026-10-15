package com.example.mednav.cms;

import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mednav.cms.import-local-on-startup", havingValue = "true")
public class CmsLocalDatasetImporter implements ApplicationRunner {

    private final CmsSnapshotImportService importService;
    private final Path dataDirectory;

    public CmsLocalDatasetImporter(
            CmsSnapshotImportService importService,
            @Value("${mednav.cms.data-dir:../dataset}") Path dataDirectory) {
        this.importService = importService;
        this.dataDirectory = dataDirectory;
    }

    @Override
    public void run(ApplicationArguments args) {
        importService.importSnapshot(Arrays.stream(CmsDataset.values())
                .map(dataset -> CmsImportFile.local(
                        dataset, dataDirectory.resolve(dataset.defaultFileName())))
                .toList());
    }
}
