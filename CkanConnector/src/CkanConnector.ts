import { AspectDefinition, AspectDefinitionsApi, Record } from './generated/registry/api';
import Ckan, { CkanDataset } from './Ckan';
import Registry from './Registry';
import AsyncPage, { forEachAsync } from './AsyncPage';

export interface AspectBuilder {
    aspectDefinition: AspectDefinition,
    builderFunctionString: string
}

export interface CkanConnectorOptions {
    ckan: Ckan,
    registry: Registry,
    aspectBuilders?: AspectBuilder[],
    ignoreHarvestSources?: string[],
    maxConcurrency?: number
}

interface CompiledAspect {
    id: string,
    builderFunction: Function
}

interface Aspects {
    [propName: string]: any;
}

export class CkanConnectionResult {
    public aspectDefinitionsConnected: number = 0;
    public datasetsConnected: number = 0;
    public errors: Error[] = [];
}

export default class CkanConnector {
    private ckan: Ckan;
    private registry: Registry;
    private ignoreHarvestSources: string[];
    private maxConcurrency: number;

    public aspectBuilders: AspectBuilder[]

    constructor({
        ckan,
        registry,
        aspectBuilders = [],
        ignoreHarvestSources = [],
        maxConcurrency = 6
    }: CkanConnectorOptions) {
        this.ckan = ckan;
        this.registry = registry;
        this.aspectBuilders = aspectBuilders.slice();
        this.ignoreHarvestSources = ignoreHarvestSources.slice();
        this.maxConcurrency = maxConcurrency;
    }

    /**
     * Queries CKAN and pushes discovered datasets to the registry.  The necessary aspect definitions
     * are first created in the registry.  If creation of an aspect definition fails (after all retries
     * have been exhausted), no records will be created and the promise will resolve with a
     * {@link CkanConnectionResult} containing the errors.
     * 
     * @returns {Promise<CkanConnectionResult>}
     * 
     * @memberOf CkanConnector
     */
    async run(): Promise<CkanConnectionResult> {
        const templates = this.aspectBuilders.map(builder => ({
            id: builder.aspectDefinition.id,
            builderFunction: new Function('dataset', 'source', builder.builderFunctionString)
        }));

        const connectionResult = new CkanConnectionResult();

        const aspectBuilderPage = AsyncPage.create<AspectBuilder[]>(current => current ? undefined : Promise.resolve(this.aspectBuilders));
        await forEachAsync(aspectBuilderPage, this.maxConcurrency, async aspectBuilder => {
            const aspectDefinitionOrError = await this.registry.putAspectDefinition(aspectBuilder.aspectDefinition);
            if (aspectDefinitionOrError instanceof Error) {
                connectionResult.errors.push(aspectDefinitionOrError);
            } else {
                connectionResult.aspectDefinitionsConnected++;
            }
        });

        // If there were errors creating the aspect definitions, don't try to create records.
        if (connectionResult.errors.length > 0) {
            return connectionResult;
        }

        const packagePages = this.ckan.packageSearch(this.ignoreHarvestSources);
        const datasets = packagePages.map(packagePage => packagePage.result.results);

        await forEachAsync(datasets, this.maxConcurrency, async dataset => {
            const recordOrError = await this.registry.putRecord(this.datasetToRecord(templates, dataset));
            if (recordOrError instanceof Error) {
                connectionResult.errors.push(recordOrError);
            } else {
                ++connectionResult.datasetsConnected;
            }
        });

        return connectionResult;
    }

    private datasetToRecord(templates: CompiledAspect[], dataset: CkanDataset): Record {
        const aspects: Aspects = {};
        templates.forEach(aspect => {
            aspects[aspect.id] = aspect.builderFunction(dataset, this.ckan);
        });

        return {
            id: dataset.id,
            name: dataset.title || dataset.name,
            aspects: aspects
        };
    }
}