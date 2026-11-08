"""Black-box capability contract, runnable unchanged on the committed known-before."""
from click.testing import CliRunner
from jseval.cli import main


def test_explicit_frozen_mode_is_available_at_the_real_cli():
    cli = CliRunner()
    result = cli.invoke(main, ['quality', 'prepare-frozen', '--help'])
    assert result.exit_code == 0, result.output
    result = cli.invoke(main, ['quality', 'check', '--help'])
    assert result.exit_code == 0 and '--frozen-indexes' in result.output, result.output
