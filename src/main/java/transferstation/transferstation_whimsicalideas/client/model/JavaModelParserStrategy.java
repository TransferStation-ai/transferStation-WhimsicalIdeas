package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

public class JavaModelParserStrategy implements ModelParserStrategy {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getPlatformName() {
        return "Java (Cross-Platform)";
    }

    @Override
    public MdlDataTypes.ParsedModel parseMdl(byte[] data) throws IOException {
        return MdlParser.parse(data);
    }

    @Override
    public VvdParser.ParsedVvd parseVvd(byte[] data) throws IOException {
        return VvdParser.parse(data);
    }

    @Override
    public VtxParser.ParsedVtx parseVtx(byte[] data) throws IOException {
        return VtxParser.parse(data);
    }

    @Override
    public SourceModelData loadModel(Path packageDir) throws IOException {
        return ModelLoadManager.loadModel(packageDir);
    }

    @Override
    public void clearCache() {
        ModelLoadManager.clearCache();
    }
}
