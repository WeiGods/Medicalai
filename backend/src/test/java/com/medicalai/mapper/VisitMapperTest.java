package com.medicalai.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class VisitMapperTest {
    @Test
    void deletesExtractionVersionsBeforeTheirSourceSnapshots() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<String> statements = new ArrayList<>();
        doAnswer(invocation -> {
            statements.add(invocation.getArgument(0, String.class));
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));

        new VisitMapper(jdbc).deleteCancelledVisit(UUID.randomUUID(), UUID.randomUUID());

        int extractionVersions = indexOf(statements, "DELETE FROM clinical_extraction_version");
        int extraction = indexOf(statements, "DELETE FROM clinical_extraction WHERE");
        int snapshots = indexOf(statements, "DELETE FROM dialogue_snapshot WHERE");
        assertTrue(extractionVersions >= 0 && extractionVersions < extraction,
                "必须先清理信息提取版本，再清理信息提取主记录");
        assertTrue(extraction < snapshots,
                "必须在删除快照前清理所有引用快照的信息提取记录");
    }

    private int indexOf(List<String> statements, String fragment) {
        for (int index = 0; index < statements.size(); index++) {
            if (statements.get(index).contains(fragment)) return index;
        }
        return -1;
    }
}
