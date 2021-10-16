package net.celestialdata.plexbotencoder.utilities;

@SuppressWarnings({"unused"})
public enum FileType {
    AVI(".avi"),
    DIVX(".divx"),
    FLV(".flv"),
    M4V(".m4v"),
    MKV(".mkv"),
    MP4(".mp4"),
    MPEG(".mpeg"),
    MPG(".mpg"),
    WMV(".wmv"),
    SRT(".srt"),
    SMI(".smi"),
    SSA(".ssa"),
    ASS(".ass"),
    VTT(".vtt"),
    UNKNOWN("."),
    MAGNET(".magnet"),
    TORRENT(".torrent");

    public static String[] mediaFileExtensions = {
            FileType.AVI.getTypeString(),
            FileType.DIVX.getTypeString(),
            FileType.FLV.getTypeString(),
            FileType.M4V.getTypeString(),
            FileType.MKV.getTypeString(),
            FileType.MP4.getTypeString(),
            FileType.MPEG.getTypeString(),
            FileType.MPG.getTypeString(),
            FileType.WMV.getTypeString()
    };

    public static String[] subtitleFileExtensions = {
            FileType.SRT.getTypeString(),
            FileType.SMI.getTypeString(),
            FileType.SSA.getTypeString(),
            FileType.ASS.getTypeString(),
            FileType.VTT.getTypeString()
    };

    private final String extension;

    FileType(String extension) {
        this.extension = extension;
    }

    public String getExtension() {
        return this.extension;
    }

    public String getTypeString() {
        return this.extension.replace(".", "");
    }

    public boolean isVideo() {
        return this == AVI || this == DIVX || this == FLV || this == M4V || this == MKV || this == MP4 || this == MPEG || this == MPG || this == WMV;
    }

    public static boolean isVideo(String filename) {
        return determineFiletype(filename).isVideo();
    }

    public boolean isSubtitle() {
        return this == SRT || this == SMI || this == SSA || this == ASS || this == VTT;
    }

    public static boolean isSubtitle(String filename) {
        return determineFiletype(filename).isSubtitle();
    }

    public static FileType determineFiletype(String filename) {
        FileType fileType = FileType.UNKNOWN;

        if (filename.endsWith(FileType.AVI.extension)) {
            fileType = FileType.AVI;
        } else if (filename.endsWith(FileType.DIVX.extension)) {
            fileType = FileType.DIVX;
        } else if (filename.endsWith(FileType.FLV.extension)) {
            fileType = FileType.FLV;
        } else if (filename.endsWith(FileType.M4V.extension)) {
            fileType = FileType.M4V;
        } else if (filename.endsWith(FileType.MKV.extension)) {
            fileType = FileType.MKV;
        } else if (filename.endsWith(FileType.MP4.extension)) {
            fileType = FileType.MP4;
        } else if (filename.endsWith(FileType.MPEG.extension)) {
            fileType = FileType.MPEG;
        } else if (filename.endsWith(FileType.MPG.extension)) {
            fileType = FileType.MPG;
        } else if (filename.endsWith(FileType.WMV.extension)) {
            fileType = FileType.WMV;
        } else if (filename.endsWith(FileType.SRT.extension)) {
            fileType = FileType.SRT;
        } else if (filename.endsWith(FileType.SMI.extension)) {
            fileType = FileType.SMI;
        } else if (filename.endsWith(FileType.SSA.extension)) {
            fileType = FileType.SSA;
        } else if (filename.endsWith(FileType.ASS.extension)) {
            fileType = FileType.ASS;
        } else if (filename.endsWith(FileType.VTT.extension)) {
            fileType = FileType.VTT;
        } else if (filename.endsWith(FileType.MAGNET.extension)) {
            fileType = FileType.MAGNET;
        } else if (filename.endsWith(FileType.TORRENT.extension)) {
            fileType = FileType.TORRENT;
        }

        return fileType;
    }
}