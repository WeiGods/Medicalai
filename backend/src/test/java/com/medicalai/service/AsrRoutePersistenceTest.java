package com.medicalai.service;
import static org.mockito.Mockito.*;
import com.medicalai.mapper.RecordingMapper;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
class AsrRoutePersistenceTest {
    @ParameterizedTest @ValueSource(strings={"LOCAL","DASHSCOPE"})
    void savesChosenRoute(String provider) {
        var jdbc = mock(JdbcTemplate.class);
        var mapper = new RecordingMapper(jdbc);
        UUID visit=UUID.randomUUID(), id=mapper.createAsrJob(visit,provider);
        // 建任务时同时落下角色识别路由（这里未指定，落 NULL 表示跟随全局默认）
        verify(jdbc).update(contains("'PENDING',?"),eq(id),eq("ASR_TRANSCRIBE"),eq(visit),isNull(),
                eq("asr:"+visit+":"+id),eq(provider),isNull());
    }
}
