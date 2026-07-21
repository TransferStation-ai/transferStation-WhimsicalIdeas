#ifndef MODEL_DIAGNOSTICS_H
#define MODEL_DIAGNOSTICS_H

#include "studio_types.h"
#include "mdl_parser.h"
#include "vvd_parser.h"
#include "vtx_parser.h"

#include <string>
#include <vector>
#include <map>
#include <filesystem>

namespace fs = std::filesystem;

class ModelDiagnostics {
public:
    struct ModelGroup {
        fs::path dir;
        std::string baseName;
        fs::path mdl;
        fs::path vvd;
        fs::path vtx;
        fs::path phy;

        bool isComplete() const {
            return !mdl.empty() && !vvd.empty() && !vtx.empty();
        }
    };

    struct DiagnosticResult {
        std::string modelName;
        bool complete;
        bool hasPhy;
        std::vector<std::string> warnings;
        std::vector<std::string> infoFields;
        std::map<std::string, uint32_t> checksums;

        bool hasIssues() const {
            return !warnings.empty() || !complete;
        }
    };

    static std::vector<ModelGroup> findModelGroups(const fs::path& rootDir);

    static DiagnosticResult diagnoseFile(const fs::path& filePath);

    static DiagnosticResult diagnoseGroup(const ModelGroup& group);

    static std::vector<DiagnosticResult> diagnoseDirectory(const fs::path& rootDir);

    static std::string formatResults(const std::vector<DiagnosticResult>& results);

private:
    static std::vector<uint8_t> readFile(const fs::path& path);

    static void diagnoseMdl(const std::vector<uint8_t>& data, DiagnosticResult& result);
    static void diagnoseVvd(const std::vector<uint8_t>& data, DiagnosticResult& result);
    static void diagnoseVtx(const std::vector<uint8_t>& data, DiagnosticResult& result);
    static void diagnosePhy(const std::vector<uint8_t>& data, DiagnosticResult& result);

    static void checkChecksumConsistency(const ModelGroup& group, DiagnosticResult& result);
};

#endif // MODEL_DIAGNOSTICS_H