package dev.agenvas.referencecatalog;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ReferenceCatalogSiteMigrationPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11-alpine");
    @Test void addsSiteDeliveryWithoutRewritingExistingGithubSourcesAndSnapshots() throws Exception {
        flyway("55").migrate();
        try(var db=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var sql=db.createStatement()){
            sql.execute("insert into reference_catalog_source(id,name,owner,repository,ref,manifest_path,format,created_at) values ('legacy','Original fixture','fixture','repo','main','catalog.json','OPEN_POSE_COSTUME',now())");
            sql.execute("insert into reference_catalog_snapshot(id,source_id,commit_sha,manifest_hash,license,entries_json,created_at) values ('00000000-0000-4000-8000-000000000001','legacy',repeat('a',40),repeat('b',64),'UPSTREAM_RESTRICTED','{\"schemaVersion\":1,\"entries\":[]}',now())");
        }
        assertThat(flyway("56").migrate().migrationsExecuted).isEqualTo(1);
        try(var db=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var sql=db.createStatement()){
            try(var row=sql.executeQuery("select source_type from reference_catalog_source where id='legacy'")){assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("GITHUB");}
            try(var row=sql.executeQuery("select commit_sha from reference_catalog_snapshot where source_id='legacy'")){assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("a".repeat(40));}
            sql.execute("insert into reference_catalog_source(id,name,owner,repository,ref,manifest_path,format,source_type,created_at) values ('site','Site fixture','fixture','repo','main','data/catalog.json','OPEN_POSE_COSTUME','OMILIC',now())");
            sql.execute("insert into reference_catalog_snapshot(id,source_id,commit_sha,manifest_hash,license,entries_json,created_at) values ('00000000-0000-4000-8000-000000000002','site',null,repeat('c',64),'UPSTREAM_RESTRICTED','{\"schemaVersion\":1,\"entries\":[]}',now())");
        }
        assertThat(flyway("56").migrate().migrationsExecuted).isZero();
    }
    Flyway flyway(String target){return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).locations("classpath:db/migration").target(target).load();}
}
