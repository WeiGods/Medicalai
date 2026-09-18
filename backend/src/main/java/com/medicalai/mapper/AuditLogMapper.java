package com.medicalai.mapper;

import com.medicalai.domain.AuditAction;
import com.medicalai.domain.AuditLog;
import com.medicalai.dto.AuditLogQueryRequest;
import com.medicalai.vo.AuditLogVO;
import com.medicalai.vo.AuditOperatorVO;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditLogMapper {
    private static final String SUPPORTED_ACTIONS = Arrays.stream(AuditAction.values())
            .map(action -> "'" + action.name() + "'")
            .collect(Collectors.joining(","));
    private static final String QUERY_BASE = """
            FROM audit_log a
            LEFT JOIN doctor d ON d.id=a.doctor_id
            LEFT JOIN visit v ON v.id=a.visit_id
            WHERE a.action IN (%s)
            """.formatted(SUPPORTED_ACTIONS);

    private final JdbcTemplate jdbc;

    public AuditLogMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditLog log) {
        jdbc.update("""
                INSERT INTO audit_log(id,doctor_id,visit_id,action,resource_id,resource_type,result,detail,client_ip)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, log.id(), log.doctorId(), log.visitId(), log.action().name(), log.resourceId(),
                log.resourceType().name(), log.result().name(), log.detail(), log.clientIp());
    }

    public long count(AuditLogQueryRequest request) {
        Query query = queryOf(request);
        Long total = jdbc.queryForObject("SELECT count(*) " + QUERY_BASE + query.where(), Long.class,
                query.parameters().toArray());
        return total == null ? 0L : total;
    }

    public List<AuditLogVO> list(AuditLogQueryRequest request) {
        Query query = queryOf(request);
        int pageSize = request.normalizedPageSize();
        int offset = (request.normalizedPage() - 1) * pageSize;
        List<Object> parameters = new ArrayList<>(query.parameters());
        parameters.add(pageSize);
        parameters.add(offset);
        return jdbc.query("""
                SELECT a.id,a.action,a.result,d.display_name,v.patient_name_snapshot,v.visit_no,
                       a.detail,a.client_ip,a.created_at
                """ + QUERY_BASE + query.where() + " ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",
                (resultSet, rowNum) -> new AuditLogVO(resultSet.getObject("id", java.util.UUID.class),
                        resultSet.getString("action"), resultSet.getString("result"),
                        resultSet.getString("display_name"), resultSet.getString("patient_name_snapshot"),
                        resultSet.getString("visit_no"), resultSet.getString("detail"),
                        resultSet.getString("client_ip"), DatabaseDateTime.getInstant(resultSet, "created_at")),
                parameters.toArray());
    }

    public List<AuditOperatorVO> listOperators() {
        return jdbc.query("""
                SELECT id,display_name FROM doctor
                WHERE status='ACTIVE' AND role IN ('DOCTOR','DEPARTMENT_HEAD','ADMIN')
                ORDER BY display_name,id
                """, (resultSet, rowNum) -> new AuditOperatorVO(resultSet.getObject("id", java.util.UUID.class),
                resultSet.getString("display_name")));
    }

    private Query queryOf(AuditLogQueryRequest request) {
        StringBuilder where = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        LocalDate from = request.from();
        LocalDate to = request.to();
        if (from != null) {
            where.append(" AND a.created_at>=?");
            parameters.add(from.atStartOfDay());
        }
        if (to != null) {
            where.append(" AND a.created_at<?");
            parameters.add(to.plusDays(1).atStartOfDay());
        }
        if (request.doctorId() != null) {
            where.append(" AND a.doctor_id=?");
            parameters.add(request.doctorId());
        }
        AuditAction action = request.action();
        if (action != null) {
            where.append(" AND a.action=?");
            parameters.add(action.name());
        }
        return new Query(where.toString(), parameters);
    }

    private record Query(String where, List<Object> parameters) {
    }
}
