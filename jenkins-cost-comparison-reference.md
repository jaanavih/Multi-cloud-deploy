# Jenkins Cost Comparison Quick Reference

## Simple setup (no Global Pipeline Library required)

Default **`Jenkinsfile`** loads **`lib/costComparison.groovy`** after `checkout scm`.

## Files

- `lib/costComparison.groovy` — cost estimates + HTML (loaded via `load`)
- `Jenkinsfile` — Option A: cost → `input()` → deploy
- `Jenkinsfile.cost-gate` — symlink → `Jenkinsfile`
- `Jenkinsfile.parameters-first` — legacy: `CLOUD_PROVIDER` on first screen
- `Jenkinsfile.cost-only` — analysis-only
- `cost-comparison-pipeline.jenkinsfile` — standalone cost pipeline

## Usage in a pipeline

After `checkout scm`:

```groovy
def costLib = load 'lib/costComparison.groovy'
def costResults = costLib.runCostComparison([
    awsRegion: 'ap-southeast-1',
    gcpRegion: 'asia-southeast1',
    hoursPerMonth: 730
])
```

## Cost factors (baseline model)

- Cluster management fees  
- Compute instances  
- Load balancers  
- Storage  
- Data transfer  
- Networking  

## Customization

Edit **`lib/costComparison.groovy`** for pricing, regions, and instance assumptions.