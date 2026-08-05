param(
    [Parameter(Mandatory = $true)]
    [string] $ExecutablePath,

    [Parameter(Mandatory = $true)]
    [string] $LaunchProfilePath
)

& $ExecutablePath --launch-profile $LaunchProfilePath
exit $LASTEXITCODE
