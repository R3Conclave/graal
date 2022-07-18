# GraalVM Conclave fork

This is a fork of the [Oracle GraalVM repo](https://github.com/oracle/graal) with modifications to get GraalVM 
working with [Conclave](https://github.com/R3Conclave/conclave-sdk) and Intel SGX.

Unlike upstream, this repo's default branch is `conclave/master` and is initially based off the `vm-22.0.0.2`
[tag](https://github.com/R3Conclave/graal/releases/tag/vm-22.0.0.2). Use this branch as the base when making changes.
When raising a PR you may need to fix the default merge settings so that it's using `conclave/master` as the base. When 
upgrading the branch to the next release, make sure it's based off the tag for that release, and not the HEAD 
of the `release/graal-vm/*` branch.

The modifications to upstream are scattered through the codebase, but are surrounded by `CONCLAVE start` and 
`CONCLAVE end` comments to help facilitate merge conflicts. There is also a Docker image and scripts contained in 
this directory to make building easier.

## License

This repo has the same license as [upstream](https://github.com/oracle/graal#license).
