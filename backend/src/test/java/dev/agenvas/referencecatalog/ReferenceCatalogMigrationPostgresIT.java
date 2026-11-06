package dev.agenvas.referencecatalog;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Additive upgrade and database-enforced immutable snapshot evidence using synthetic metadata. */
@Testcontainers
class ReferenceCatalogMigrationPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11-alpine");
    @Test void upgradeKeepsExistingProjectsAndDocumentsEveryCatalogDatabaseObject() throws Exception {
        flyway("49").migrate();UUID owner=UUID.randomUUID(),project=UUID.randomUUID();
        try(var db=connection()){
            execute(db,"insert into app_user(id,login_name,password_hash,status,created_at) values (?,'catalog-upgrade','synthetic','ACTIVE',now())",owner);
            execute(db,"insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at) values (?,?,'Keep exact project','LANDSCAPE_16_9','ACTIVE',now(),now())",project,owner);
        }
        assertThat(flyway("52").migrate().migrationsExecuted).isEqualTo(1);
        try(var db=connection()){
            assertThat(text(db,"select name from project where id='"+project+"'")).isEqualTo("Keep exact project");
            assertThat(text(db,"select count(*)::text from reference_catalog_source")).isEqualTo("0");
            String tables="('reference_catalog_source','reference_catalog_snapshot','reference_catalog_import')";
            assertThat(text(db,"select count(*)::text from pg_class c where (c.relname in "+tables+" or c.relname like 'reference_catalog_%') and c.relkind in ('r','i') and obj_description(c.oid,'pg_class') is null")).isEqualTo("0");
            assertThat(text(db,"select count(*)::text from pg_attribute a join pg_class c on c.oid=a.attrelid where c.relname in "+tables+" and a.attnum>0 and not a.attisdropped and col_description(c.oid,a.attnum) is null")).isEqualTo("0");
            assertThat(text(db,"select count(*)::text from pg_constraint k join pg_class c on c.oid=k.conrelid where c.relname in "+tables+" and obj_description(k.oid,'pg_constraint') is null")).isEqualTo("0");
            UUID snapshot=UUID.randomUUID();
            execute(db,"insert into reference_catalog_source(id,name,owner,repository,ref,manifest_path,format,created_at) values ('synthetic','Synthetic','owner','repo',repeat('a',40),'pack.json','AGENVAS_REFERENCE_PACK',now())");
            execute(db,"insert into reference_catalog_snapshot(id,source_id,commit_sha,manifest_hash,license,entries_json,created_at) values (?,'synthetic',repeat('a',40),repeat('b',64),'CC0-1.0','{\"schemaVersion\":1,\"entries\":[]}',now())",snapshot);
            assertThatThrownBy(()->execute(db,"update reference_catalog_snapshot set license='changed' where id=?",snapshot)).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->execute(db,"delete from reference_catalog_snapshot where id=?",snapshot)).isInstanceOf(SQLException.class);
        }
        assertThat(flyway("52").migrate().migrationsExecuted).isZero();
    }
    Flyway flyway(String target){return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).locations("classpath:db/migration").target(target).load();}
    Connection connection() throws SQLException{return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
    void execute(Connection db,String sql,Object... values) throws SQLException{try(var p=db.prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();}}
    String text(Connection db,String sql) throws SQLException{try(var p=db.createStatement();var r=p.executeQuery(sql)){r.next();return r.getString(1);}}
}
