# Multi-cloud-deploy

Canonical repo: [github.com/jaanavih/Multi-cloud-deploy](https://github.com/jaanavih/Multi-cloud-deploy).

Multi-cloud deployment pipeline with **cost comparison** before deployment. Choose between AWS EKS and GCP GKE based on cost analysis.

- **Default**: `Jenkinsfile` shows cost reports → `input()` to choose AWS/GCP → deploy
- **Legacy**: `Jenkinsfile.parameters-first` picks cloud on first parameter screen
- **Cost only**: `Jenkinsfile.cost-only` for analysis without deployment

See **`COST_COMPARISON_README.md`** for setup details.
