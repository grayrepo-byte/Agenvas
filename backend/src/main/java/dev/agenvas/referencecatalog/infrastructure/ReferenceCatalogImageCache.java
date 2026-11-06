package dev.agenvas.referencecatalog.infrastructure;

import static dev.agenvas.referencecatalog.application.ReferenceCatalogValidation.*;
import dev.agenvas.asset.application.AssetProperties;
import dev.agenvas.referencecatalog.application.ReferenceCatalogCacheProperties;
import dev.agenvas.shared.error.ApiProblemException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Content-addressed rebuildable files. One OS lock bounds downloads and serializes clear/publication. */
@Component
public class ReferenceCatalogImageCache {
    private static final Object MONITOR=new Object();
    private static final int MAX_FILES=10_000;
    private final Path root;private final long quota;
    public ReferenceCatalogImageCache(ReferenceCatalogCacheProperties properties,AssetProperties assets){
        root=(properties.root()==null?assets.root().resolve(".reference-catalog-cache"):properties.root()).toAbsolutePath().normalize();quota=properties.quotaBytes();
    }
    public record Stats(long quotaBytes,long usedBytes,int fileCount,long availableBytes,long protectedBytes,int protectedFileCount){}
    public record Removed(int removedFiles,long removedBytes,Stats stats){}
    public byte[] get(String sha,long size,String type,boolean populate,Runnable authorize,Supplier<byte[]> fetch){
        return getAndPublish(sha,size,type,populate,authorize,fetch,java.util.function.Function.identity());
    }
    public <T>T getAndPublish(String sha,long size,String type,boolean populate,Runnable authorize,Supplier<byte[]> fetch,java.util.function.Function<byte[],T> publish){
        validHash(sha);authorize.run();
        if(!populate)return publish.apply(readOnly(sha,size,type,authorize));
        return locked(()->publish.apply(load(sha,size,type,authorize,fetch)));
    }
    private byte[] load(String sha,long size,String type,Runnable authorize,Supplier<byte[]> fetch){
            authorize.run();Path target=file(sha);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
                if(Files.isSymbolicLink(target))throw storage();
                try{return read(target,sha,size,type,authorize);}
                catch(ApiProblemException corrupt){if(!corrupt.code().equals("REFERENCE_CATALOG_CACHE_CORRUPT"))throw corrupt;delete(target);}
            }
            Stats before=statsUnlocked();if(size>quota-before.usedBytes() || before.fileCount()>=MAX_FILES)throw full();
            byte[] bytes=fetch.get();image(bytes,sha,size,type);authorize.run();
            Path temporary=null;
            try{
                temporary=Files.createTempFile(root,".download-",".tmp");
                try(var output=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                    ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
                }
                Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);temporary=null;
                authorize.run();return bytes;
            }catch(IOException failure){throw storage();}
            finally{if(temporary!=null)delete(temporary);}
    }
    private byte[] readOnly(String sha,long size,String type,Runnable authorize){
        try{if(!existsRoot() || !Files.exists(file(sha),LinkOption.NOFOLLOW_LINKS))throw miss();return read(file(sha),sha,size,type,authorize);}
        catch(IOException failure){throw storage();}
    }
    private byte[] read(Path target,String sha,long size,String type,Runnable authorize){
        if(Files.isSymbolicLink(target))throw storage();
        try(var channel=FileChannel.open(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            long actualSize=channel.size();if(actualSize<=0 || actualSize>MAX_IMAGE_BYTES)throw corrupt();
            ByteBuffer bytes=ByteBuffer.allocate((int)actualSize);while(bytes.hasRemaining()){if(channel.read(bytes)<0)throw corrupt();}
            String actualType;
            try{actualType=actualType(bytes.array());image(bytes.array(),sha,actualSize,actualType);}catch(ApiProblemException failure){throw corrupt();}
            // A caller's bad manifest cannot evict checksum-valid bytes belonging to another cache user.
            if(size!=actualSize || !Objects.equals(type,actualType))throw metadataConflict();
            authorize.run();return bytes.array();
        }catch(NoSuchFileException failure){throw miss();}
        catch(IOException failure){throw storage();}
    }
    private static String actualType(byte[] bytes){
        try(var input=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))){
            var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw corrupt();var reader=readers.next();
            try{return switch(reader.getFormatName().toLowerCase(java.util.Locale.ROOT)){case "png"->"image/png";case "jpeg","jpg"->"image/jpeg";case "webp"->"image/webp";default->throw corrupt();};}finally{reader.dispose();}
        }catch(IOException failure){throw corrupt();}
    }
    /** Presence is a UI hint; reads still authenticate and revalidate checksum, size, type and decoding. */
    public boolean contains(String sha,Long size){
        if(sha==null || size==null)return false;validHash(sha);
        try{return existsRoot() && Files.isRegularFile(file(sha),LinkOption.NOFOLLOW_LINKS) && Files.size(file(sha))==size;}
        catch(IOException|ApiProblemException failure){return false;}
    }
    public Stats stats(){
        try{return existsRoot()?statsUnlocked():new Stats(quota,0,0,quota,0,0);}catch(IOException failure){throw storage();}
    }
    public Removed clear(Set<String> hashes){
        if(hashes!=null)hashes.forEach(ReferenceCatalogImageCache::validHash);
        return locked(()->{int files=0;long bytes=0;
            for(Path file:files()){
                String name=file.getFileName().toString();if(hashes!=null && !hashes.contains(name.substring(0,64)))continue;
                if(Files.isSymbolicLink(file))throw storage();
                try{bytes+=Files.size(file);Files.delete(file);files++;}catch(IOException failure){throw storage();}
            }
            return new Removed(files,bytes,statsUnlocked());
        });
    }
    public Stats stats(Set<String> protectedHashes){
        Stats basic=stats();long bytes=0;int count=0;
        for(String sha:protectedHashes){validHash(sha);Path file=file(sha);try{if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)){bytes+=Files.size(file);count++;}}catch(IOException failure){throw storage();}}
        return new Stats(basic.quotaBytes(),basic.usedBytes(),basic.fileCount(),basic.availableBytes(),bytes,count);
    }
    public Removed clearProtecting(Set<String> hashes,Supplier<Set<String>> protectedHashes){
        return locked(()->{
            Set<String> protectedSet=protectedHashes.get();Set<String> selected=new HashSet<>();
            for(Path file:files()){String sha=file.getFileName().toString().substring(0,64);if((hashes==null || hashes.contains(sha)) && !protectedSet.contains(sha))selected.add(sha);}
            int count=0;long bytes=0;for(String sha:selected){Path file=file(sha);try{if(Files.isSymbolicLink(file))throw storage();bytes+=Files.size(file);Files.delete(file);count++;}catch(IOException failure){throw storage();}}
            return new Removed(count,bytes,stats(protectedSet));
        });
    }
    /** Cross-process identity lock keeps first-observed integrity publication and replay together. */
    public <T>T withIdentityLock(UUID id,Supplier<T> action){
        synchronized(MONITOR){
            try{Files.createDirectories(root.getParent());if(Files.exists(root,LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(root))throw storage();Files.createDirectories(root);
                if(!existsRoot())throw storage();
                try(var channel=FileChannel.open(root.resolve(".identity-"+id+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lock=channel.lock()){return action.get();}
            }catch(IOException failure){throw storage();}
        }
    }
    public long quotaBytes(){return quota;}
    private <T>T locked(Supplier<T> action){
        synchronized(MONITOR){
            try{
                Path parent=root.getParent();if(parent==null)throw storage();Files.createDirectories(parent);
                if(Files.exists(root,LinkOption.NOFOLLOW_LINKS) && (!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)))throw storage();
                Files.createDirectories(root);if(!existsRoot())throw storage();
                try(var channel=FileChannel.open(root.resolve(".cache.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lock=channel.lock()){
                    if(!existsRoot())throw storage();
                    // Only our global lock owns temporary writes. A leftover temp is an interrupted write.
                    try(var stream=Files.newDirectoryStream(root,".download-*.tmp")){for(Path abandoned:stream){if(Files.isSymbolicLink(abandoned))throw storage();Files.deleteIfExists(abandoned);}}
                    return action.get();
                }
            }catch(IOException failure){throw storage();}
        }
    }
    private boolean existsRoot() throws IOException {
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return false;
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))throw storage();
        return true;
    }
    private List<Path> files(){
        List<Path> files=new ArrayList<>();try(var stream=Files.newDirectoryStream(root,"*.image")){
            for(Path file:stream){if(!file.getFileName().toString().matches("[a-f0-9]{64}\\.image"))continue;
                if(files.size()>=MAX_FILES)throw storage();files.add(file);}
        }catch(IOException failure){throw storage();}return files;
    }
    private Stats statsUnlocked(){long bytes=0;int count=0;for(Path file:files()){
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))throw storage();
        try{bytes=Math.addExact(bytes,Files.size(file));count++;}catch(IOException|ArithmeticException failure){throw storage();}
    }return new Stats(quota,bytes,count,Math.max(0,quota-bytes),0,0);}
    private Path file(String sha){return root.resolve(sha+".image");}
    private static void validHash(String sha){if(sha==null || !sha.matches("[a-f0-9]{64}"))throw invalid();}
    private static void delete(Path file){try{Files.deleteIfExists(file);}catch(IOException failure){throw storage();}}
    private static ApiProblemException storage(){return problem(HttpStatus.INSUFFICIENT_STORAGE,"REFERENCE_CATALOG_CACHE_STORAGE_FAILED","api.reference-catalog.cacheStorageFailed");}
    private static ApiProblemException full(){return problem(HttpStatus.INSUFFICIENT_STORAGE,"REFERENCE_CATALOG_CACHE_FULL","api.reference-catalog.cacheFull");}
    private static ApiProblemException miss(){return problem(HttpStatus.SERVICE_UNAVAILABLE,"REFERENCE_CATALOG_CACHE_MISS","api.reference-catalog.cacheMiss");}
    private static ApiProblemException metadataConflict(){return problem(HttpStatus.CONFLICT,"REFERENCE_CATALOG_CACHE_METADATA_CONFLICT","api.reference-catalog.cacheMetadataConflict");}
    private static ApiProblemException corrupt(){return problem(HttpStatus.CONFLICT,"REFERENCE_CATALOG_CACHE_CORRUPT","api.reference-catalog.cacheCorrupt");}
}
