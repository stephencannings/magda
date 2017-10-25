import sleuther from "@magda/sleuther-framework/dist/index";
import onRecordFound from "./onRecordFound";
import brokenLinkAspectDef from "./brokenLinkAspectDef";
import datasetQualityAspectDef from "@magda/sleuther-framework/dist/common-aspect-defs/datasetQualityAspectDef";
import commonYargs from "@magda/sleuther-framework/dist/commonYargs";

const ID = "sleuther-broken-link";

const argv = commonYargs(ID, 6111, "http://localhost:6111").argv;

function sleuthBrokenLinks() {
    return sleuther({
        argv,
        id: ID,
        aspects: ["dataset-distributions"],
        optionalAspects: [],
        async: true,
        writeAspectDefs: [brokenLinkAspectDef, datasetQualityAspectDef],
        onRecordFound: (record, registry) =>
            onRecordFound(record, registry, argv.retries)
    });
}

sleuthBrokenLinks().catch(e => {
    console.error("Error: " + e.message, e);
    process.exit(1);
});
