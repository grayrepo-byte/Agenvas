package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V38->V39 preserves existing grouped creative work and records irreversible topology history. */
@Testcontainers
class CanvasEditMigrationPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @Test void backfillsExistingGroupsAndEnforcesCommentedProjectBoundariesAndTopologyEpochs() throws Exception {
        migrate("38"); UUID owner=UUID.randomUUID(), project=UUID.randomUUID(), other=UUID.randomUUID();
        UUID artifact=UUID.randomUUID(), version=UUID.randomUUID(), first=UUID.randomUUID(), second=UUID.randomUUID(), group=UUID.randomUUID();
        try (Connection db = connection()) {
            execute(db,"insert into app_user(id,login_name,password_hash,status,created_at) values (?,'canvas-upgrade','synthetic-hash','ACTIVE',now())", owner);
            for(UUID id: new UUID[]{project,other}) execute(db,"insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at) values (?,?,'Existing','LANDSCAPE_16_9','ACTIVE',now(),now())",id,owner);
            execute(db,"insert into artifact(id,project_id,kind,title,created_at,updated_at) values (?,?,'IMAGE','Existing image',now(),now())",artifact,project);
            execute(db,"insert into artifact_version(id,project_id,artifact_id,version_no,schema_version,content_json,input_refs_json,created_by_kind,created_at) values (?,?,?,1,1,'{\"prompt\":\"Old content\"}','[]','USER',now())",version,project,artifact);
            for(UUID id:new UUID[]{first,second}) {
                execute(db,"insert into canvas_item(id,project_id,subject_type,subject_id,artifact_id,title,selected_version_id,x,y,width,height,z_index,group_id,created_at,updated_at) values (?,?,'ARTIFACT',?,?,'Existing node',?,20,40,320,240,0,?,now(),now())",id,project,artifact,artifact,version,group);
                execute(db,"insert into media_draft(project_id,canvas_item_id,prompt,created_at,updated_at) values (?,?,'Keep old draft',now(),now())",project,id);
            }
        }
        migrate("39");
        try(Connection db=connection()) {
            assertThat(text(db,"select count(*)::text from canvas_group where project_id='"+project+"' and id='"+group+"' and name='Group' and version=0 and not collapsed and not deleted")).isEqualTo("1");
            assertThat(text(db,"select count(*)::text from canvas_item where group_id='"+group+"' and selected_version_id='"+version+"' and x=20 and y=40 and version=0 and topology_epoch=0")).isEqualTo("2");
            assertThat(text(db,"select prompt from media_draft where canvas_item_id='"+first+"'")).isEqualTo("Keep old draft");
            assertThat(text(db,"select content_json->>'prompt' from artifact_version where id='"+version+"'")).isEqualTo("Old content");
            UUID foreign=UUID.randomUUID(); execute(db,"insert into canvas_group(project_id,id,name,created_at,updated_at) values (?,?,'Foreign',now(),now())",other,foreign);
            assertThatThrownBy(() -> execute(db,"update canvas_item set group_id=? where id=?",foreign,first)).isInstanceOfSatisfying(SQLException.class,error->assertThat(error.getSQLState()).isEqualTo("23503"));
            UUID edge=UUID.randomUUID();
            execute(db,"insert into canvas_connection(id,project_id,source_canvas_item_id,target_canvas_item_id,relation_type,source_artifact_version_id,created_at,updated_at) values (?,?,?,?,'MEDIA_DERIVATION',?,now(),now())",edge,project,first,second,version);
            assertThat(text(db,"select topology_epoch::text from canvas_item where id='"+first+"'")).isEqualTo("1");
            execute(db,"delete from canvas_connection where id=?",edge);
            assertThat(text(db,"select topology_epoch::text from canvas_item where id='"+first+"'")).isEqualTo("2");
            assertThat(text(db,"select topology_epoch::text from canvas_item where id='"+second+"'")).isEqualTo("2");
            execute(db,"insert into canvas_connection(id,project_id,source_canvas_item_id,target_canvas_item_id,relation_type,source_artifact_version_id,created_at,updated_at) values (?,?,?,?,'MEDIA_DERIVATION',?,now(),now())",UUID.randomUUID(),project,first,second,version);
            execute(db,"delete from canvas_item where id=?",first);
            assertThat(text(db,"select topology_epoch::text from canvas_item where id='"+second+"'")).isEqualTo("4");
            assertThat(text(db,"select version::text from canvas_item where id='"+second+"'")).isEqualTo("0");
            String tables="('canvas_group','canvas_edit_receipt','canvas_edit_command')";
            assertThat(text(db,"select count(*)::text from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relname in "+tables+" and obj_description(c.oid,'pg_class') is null")).isEqualTo("0");
            assertThat(text(db,"select count(*)::text from pg_attribute a join pg_class c on c.oid=a.attrelid where c.relname in "+tables+" and a.attnum>0 and not a.attisdropped and col_description(c.oid,a.attnum) is null")).isEqualTo("0");
            assertThat(text(db,"select count(*)::text from pg_constraint k join pg_class c on c.oid=k.conrelid where c.relname in "+tables+" and obj_description(k.oid,'pg_constraint') is null")).isEqualTo("0");
            assertThat(text(db,"select count(*)::text from pg_indexes i join pg_class c on c.relname=i.indexname where i.tablename in "+tables+" and obj_description(c.oid,'pg_class') is null")).isEqualTo("0");
            assertThatThrownBy(() -> execute(db,"insert into canvas_edit_receipt(project_id,id,operation,state,revision,content_json,created_at) values (?,?,'DISPLAY','APPLIED',0,'{}',now())",project,UUID.randomUUID())).isInstanceOfSatisfying(SQLException.class,error->assertThat(error.getSQLState()).isEqualTo("23514"));
        }
    }
    private void migrate(String target) { Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).locations("classpath:db/migration").target(target).load().migrate(); }
    private Connection connection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
    private void execute(Connection db,String sql,Object... values) throws SQLException { try(var query=db.prepareStatement(sql)) { for(int i=0;i<values.length;i++) query.setObject(i+1,values[i]); query.executeUpdate(); } }
    private String text(Connection db,String sql) throws SQLException { try(var query=db.createStatement();var rows=query.executeQuery(sql)) { if(!rows.next())throw new SQLException("Expected row");return rows.getString(1); } }
}
