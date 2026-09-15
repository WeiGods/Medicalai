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
        verify(jdbc).update(contains("'PENDING',?"),eq(id),eq("ASR_TRANSCRIBE"),eq(visit),eq("asr:"+visit+":"+id),eq(provider));
    }
}
