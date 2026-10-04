import pytest

from divvy_ml.ownership.config import DATA_DIR_ENV


@pytest.fixture(autouse=True)
def isolated_data_dir(tmp_path_factory, monkeypatch):
    """No test writes household data into the repository."""
    monkeypatch.setenv(DATA_DIR_ENV, str(tmp_path_factory.mktemp("divvy-data")))
