package dev.agenvas.referencecatalog.domain;

import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import java.net.URI;

/** Published individual reference-use scope is separate from bulk cache and distribution permission. */
public final class ReferenceCatalogPrivatePolicy {
    private ReferenceCatalogPrivatePolicy(){}
    public static String disabledReason(Source source,Entry entry){
        if(!source.enabled())return "REFERENCE_CATALOG_SOURCE_DISABLED";
        if(source.format()!=Format.OPEN_POSE_COSTUME || !source.owner().equalsIgnoreCase("hongye007") || !source.repository().equalsIgnoreCase("open-pose-costume"))return "PRIVATE_IMAGE_SOURCE_UNSUPPORTED";
        if(entry.collection().equals("custom"))return "PRIVATE_IMAGE_CUSTOM_USE_SCOPE_UNVERIFIED";
        String host;try{host=URI.create(entry.externalUrl()).getHost();}catch(IllegalArgumentException failure){return "PRIVATE_IMAGE_ARCHIVE_PATH_UNVERIFIED";}
        if("ai-costume-anime-gallery.pages.dev".equals(host))return "PRIVATE_IMAGE_ANIME_USE_SCOPE_UNVERIFIED";
        if(entry.kind()==Kind.POSE && "ai-pose-gallery.pages.dev".equals(host) || entry.kind()==Kind.COSTUME && "ai-costume-gallery.pages.dev".equals(host))return null;
        return "PRIVATE_IMAGE_ARCHIVE_PATH_UNVERIFIED";
    }
    public static String archivePath(Entry entry,Variant variant){return entry.kind().name().toLowerCase(java.util.Locale.ROOT)+"/originals/"+variant.id()+".png";}
}
