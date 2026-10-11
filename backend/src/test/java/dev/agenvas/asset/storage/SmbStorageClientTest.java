package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import dev.agenvas.shared.error.ApiProblemException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SmbStorageClientTest {
    private StorageProfile profile() { return new StorageProfile(UUID.randomUUID(), "Synthetic SMB", StorageProfile.Provider.SMB,
            "smb://nas.example.com/media/archives", "", "", "agenvas", false, 1, null, "masked", Instant.EPOCH); }
    @Test void pathsStayInTheConfiguredShareAndDomainPrincipalsAreExplicit() {
        assertThat(SmbStorageClient.path(profile(), "project/file.png")).isEqualTo("archives\\project\\file.png");
        assertThat(SmbStorageClient.principal("DOMAIN\\demo")).containsExactly("DOMAIN", "demo");
        assertThat(SmbStorageClient.principal("demo")).containsExactly("", "demo");
        for (String key : new String[] { "../file", "/file", "a\\b", "a//b" })
            assertThatThrownBy(() -> SmbStorageClient.path(profile(), key)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rangeReadsSeekDirectlyAndCloseEveryOwnedResource() throws Exception {
        var share = mock(DiskShare.class); var file = mock(File.class);
        var client = mock(SMBClient.class); var connection = mock(Connection.class); var session = mock(Session.class);
        var info = mock(FileAllInformation.class); var standard = mock(FileStandardInformation.class);
        when(info.getStandardInformation()).thenReturn(standard); when(standard.getEndOfFile()).thenReturn(10L); when(file.getFileInformation()).thenReturn(info);
        when(share.openFile(anyString(), any(), any(), any(), any(), any())).thenReturn(file);
        when(file.read(any(byte[].class), eq(4L), eq(0), eq(3))).thenAnswer(call -> {
            byte[] bytes = call.getArgument(0); System.arraycopy(new byte[] { 4, 5, 6 }, 0, bytes, 0, 3); return 3;
        });
        var storage = new SmbStorageClient(mock(StorageSettingsService.class), p -> new SmbStorageClient.Handle(client, connection, session, share));
        try (var input = storage.open(profile(), "project/file.png", 4, 3, 10)) { assertThat(input.readAllBytes()).containsExactly(4, 5, 6); }
        verify(file).close(); verify(share).close(); verify(session).close(); verify(connection).close(); verify(client).close();
        verify(file, never()).getInputStream();
    }
    @Test void uploadCreatesAnExclusiveTemporaryFileAndPublishesWithoutReplacement() throws Exception {
        var share = mock(DiskShare.class); var file = mock(File.class);
        var info = mock(FileAllInformation.class); var standard = mock(FileStandardInformation.class);
        byte[] bytes = { 1,2,3,4 };
        when(info.getStandardInformation()).thenReturn(standard); when(standard.getEndOfFile()).thenReturn(4L); when(file.getFileInformation()).thenReturn(info);
        when(share.openFile(anyString(), any(), any(), any(), any(), any())).thenReturn(file);
        when(share.openDirectory(anyString(),any(),any(),any(),any(),any())).thenReturn(mock(com.hierynomus.smbj.share.Directory.class));
        when(share.fileExists("archives\\project\\file.png")).thenReturn(false,true);
        when(file.getInputStream()).thenAnswer(call -> new java.io.ByteArrayInputStream(bytes));
        when(file.write(any(byte[].class),anyLong(),anyInt(),anyInt())).thenAnswer(call -> ((Integer)call.getArgument(3)).longValue());
        var storage = new SmbStorageClient(mock(StorageSettingsService.class), p -> new SmbStorageClient.Handle(mock(SMBClient.class),mock(Connection.class),mock(Session.class),share));
        Path source = java.nio.file.Files.createTempFile("agenvas-synthetic-smb-",".tmp");
        try {
            java.nio.file.Files.write(source,bytes); storage.put(profile(),"project/file.png",source,"image/png",4,WebDavStorageClientTest.hash(bytes));
            verify(file).rename("archives\\project\\file.png",false);
            verify(share).openFile(contains(".upload-"),any(),any(),eq(java.util.EnumSet.noneOf(com.hierynomus.mssmb2.SMB2ShareAccess.class)),
                    eq(com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_CREATE),any());
            assertThatThrownBy(() -> storage.matches(profile(),"project/file.png",4,"0".repeat(64))).isInstanceOf(ApiProblemException.class)
                    .satisfies(e -> assertThat(((ApiProblemException)e).code()).isEqualTo("STORAGE_OBJECT_CONFLICT"));
        } finally { java.nio.file.Files.delete(source); }
    }
    @Test void metadataChecksNeverDownloadAndProtocolErrorsAreSanitized() throws Exception {
        var share = mock(DiskShare.class); var file = mock(File.class);
        var info = mock(FileAllInformation.class); var standard = mock(FileStandardInformation.class);
        when(info.getStandardInformation()).thenReturn(standard); when(standard.getEndOfFile()).thenReturn(10L); when(file.getFileInformation()).thenReturn(info);
        when(share.openFile(anyString(), any(), any(), any(), any(), any())).thenReturn(file);
        var storage = new SmbStorageClient(mock(StorageSettingsService.class), p -> new SmbStorageClient.Handle(mock(SMBClient.class), mock(Connection.class), mock(Session.class), share));
        storage.verify(profile(), "project/file.png", 10, "hash"); verify(file, never()).getInputStream();
        var broken = new SmbStorageClient(mock(StorageSettingsService.class), p -> { throw new java.io.IOException("synthetic-private-address/password"); });
        assertThatThrownBy(() -> broken.matches(profile(), "project/file.png", 10, "hash")).isInstanceOf(ApiProblemException.class).hasMessageNotContaining("password");
    }
}
