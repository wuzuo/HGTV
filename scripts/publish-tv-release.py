#!/usr/bin/env python3
"""Validate and publish a prepared TV release to GitHub Releases (stdlib only)."""
import argparse
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = 'wuzuo/HGTV'
BASE = 'https://api.github.com/repos/' + REPO
CONFIG = Path.home() / '.config/hgtv/publish.json'


class Failure(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward API credentials to another host.


def request(url, method='GET', data=None, headers=None, missing=False, raw=False):
    if urllib.parse.urlsplit(url).scheme != 'https':
        raise Failure('Refusing non-HTTPS request')
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=120) as response:
            body = response.read()
            return body if raw else (json.loads(body) if body else {})
    except urllib.error.HTTPError as error:
        if missing and error.code == 404:
            return None
        # Do not print URLs, signed upload links, response bodies or tokens.
        raise Failure(f'HTTP {error.code}: request failed; check token permissions and release state') from None
    except (OSError, ValueError):
        raise Failure('Network request or JSON response failed; retry the command') from None


def configure():
    values = {'GITHUB_TOKEN': getpass.getpass('GITHUB_TOKEN (hidden): ').strip()}
    if not values['GITHUB_TOKEN']:
        raise Failure('GITHUB_TOKEN is required')
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    # Windows has no O_NOFOLLOW/fchmod; the private-file check in credentials() then applies
    # only where the platform can report POSIX modes.
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(CONFIG, flags, 0o600)
    if hasattr(os, 'fchmod'):
        os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(values, stream)
    print(f'Credentials saved locally with mode 0600: {CONFIG}')


def credentials():
    values = {}
    if CONFIG.exists():
        if CONFIG.is_symlink() or (os.name != 'nt' and stat.S_IMODE(CONFIG.stat().st_mode) & 0o077):
            raise Failure('Credential file must be a regular private file (chmod 600)')
        values = json.loads(CONFIG.read_text())
    token = os.environ.get('GITHUB_TOKEN') or values.get('GITHUB_TOKEN')
    if not token:
        raise Failure('Run --configure in your terminal or set GITHUB_TOKEN')
    return {'GITHUB_TOKEN': token}


def bundle(directory):
    """Validate the three-file bundle; the manifest must describe the APK byte for byte."""
    manifest = json.loads((directory / 'update.json').read_text())
    tag = manifest['tag']
    if not re.fullmatch(r'kotlin-v\d+\.\d+\.\d+', tag):
        raise Failure('Only versioned TV tags are supported')
    name = manifest['apk']['name']
    if Path(name).name != name or not name.endswith('.apk'):
        raise Failure('Invalid APK filename')
    files = [directory / name, directory / 'release-notes.md', directory / 'update.json']
    if manifest['packageName'] != 'io.github.wuzuo.hgtv' or manifest['minSdk'] != 23:
        raise Failure('Unexpected package or minSdk')
    apk = files[0].read_bytes()
    if len(apk) != manifest['apk']['size'] or hashlib.sha256(apk).hexdigest() != manifest['apk']['sha256']:
        raise Failure('APK does not match update.json')
    notes = files[1].read_text().strip()
    if not notes or notes != manifest['notes'].strip():
        raise Failure('Update notes differ from update.json')
    return tag, notes, files


class Publisher:
    """GitHub Releases client: stage a draft, upload assets, then publish as Latest."""

    def __init__(self, token, tag, notes):
        self.token, self.tag, self.notes = token, tag, notes
        self.title = 'HGTV v' + tag.removeprefix('kotlin-v')

    def api(self, path, method='GET', value=None, missing=False):
        headers = {'Accept': 'application/json', 'User-Agent': 'hgtv-release',
                   'Authorization': 'Bearer ' + self.token, 'X-GitHub-Api-Version': '2022-11-28'}
        data = None
        if value is not None:
            data = json.dumps(value).encode()
            headers['Content-Type'] = 'application/json'
        try:
            return request(BASE + path, method, data, headers, missing)
        except Failure as error:
            raise Failure(f"{method} {path.split(chr(63))[0]}: {error}") from None

    def get(self):
        release = self.api('/releases/tags/' + self.tag, missing=True)
        if release is not None:
            return release
        # GitHub's tag endpoint may omit drafts; find the matching authenticated draft.
        for page in range(1, 101):
            releases = self.api(f'/releases?per_page=100&page={page}')
            if not isinstance(releases, list):
                raise Failure('invalid release-list response')
            for candidate in releases:
                if candidate.get('tag_name') == self.tag:
                    return candidate
            if len(releases) < 100:
                break
        return None

    def preflight(self):
        release = self.get()
        if release is not None:
            return release
        if self.api('/git/ref/tags/' + self.tag, missing=True) is None:
            raise Failure('tag missing; push the tag before publishing')
        return None

    def stage(self, release=None):
        if release is None:
            release = self.api('/releases', 'POST',
                               dict(tag_name=self.tag, name=self.title, body=self.notes,
                                    draft=True, prerelease=False))
        return release

    def upload(self, release, file):
        existing = next((a for a in release.get('assets', []) if a.get('name') == file.name), None)
        if existing:
            verify_asset(existing, file)
            print(f'verified existing {file.name}')
            return
        data = file.read_bytes()
        url = release['upload_url'].split('{')[0]
        if urllib.parse.urlsplit(url).hostname != 'uploads.github.com':
            raise Failure('Unexpected GitHub upload host')
        request(url + '?' + urllib.parse.urlencode({'name': file.name}), 'POST', data,
                {'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/octet-stream',
                 'User-Agent': 'hgtv-release'})
        print(f'uploaded {file.name}')

    def verify(self, files):
        release = self.get()
        if release is None:
            raise Failure('staged release not readable; retry later')
        for file in files:
            asset = next((a for a in release.get('assets', []) if a.get('name') == file.name), None)
            if asset is None:
                raise Failure(f'attachment not listed: {file.name}; retry later')
            verify_asset(asset, file)

    def finish(self):
        self.api('/releases/' + str(self.get()['id']), 'PATCH',
                 dict(name=self.title, body=self.notes, draft=False, prerelease=False,
                      make_latest='true'))
        release = self.get()
        if release.get('draft') or release.get('prerelease'):
            raise Failure('release is not stable')
        if self.api('/releases/latest').get('tag_name') != self.tag:
            raise Failure('Latest verification failed')
        print(f'published {self.tag} as Latest')


def verify_asset(asset, file):
    expected = hashlib.sha256(file.read_bytes()).hexdigest()
    digest = (asset.get('digest') or '').removeprefix('sha256:')
    if digest:
        if digest != expected or asset.get('size', file.stat().st_size) != file.stat().st_size:
            raise Failure(f'Existing attachment differs: {file.name}; no files were deleted')
        return
    # Older assets may lack a digest; verify via anonymous public download instead.
    url = asset['browser_download_url']
    host = urllib.parse.urlsplit(url).hostname or ''
    if urllib.parse.urlsplit(url).scheme != 'https' or not (
        host in ('github.com', 'api.github.com') or host.endswith('.githubusercontent.com')):
        raise Failure('Unexpected download host')
    try:
        with urllib.request.urlopen(url, timeout=120) as response:
            digestor = hashlib.sha256()
            count = 0
            while chunk := response.read(1024 * 1024):
                count += len(chunk)
                if count > file.stat().st_size:
                    raise Failure(f'Remote attachment differs: {file.name}')
                digestor.update(chunk)
        if count != file.stat().st_size or digestor.hexdigest() != expected:
            raise Failure(f'Remote attachment differs: {file.name}')
    except (urllib.error.URLError, OSError):
        raise Failure(f'Cannot verify download: {file.name}; retry later') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configure', action='store_true', help='Read the hidden token into a private local file')
    parser.add_argument('--directory', type=Path, help='Prepared release directory containing three files')
    parser.add_argument('--publish', action='store_true', help='Upload to GitHub and mark Latest')
    args = parser.parse_args()
    if args.configure:
        configure()
        return
    if not args.directory:
        parser.error('--directory is required')
    tag, notes, files = bundle(args.directory)
    print(f'Validated {tag}: ' + ', '.join(file.name for file in files))
    if not args.publish:
        print('Local check only. Add --publish to upload; no credentials loaded or network writes performed.')
        return
    publisher = Publisher(credentials()['GITHUB_TOKEN'], tag, notes)
    # Every read-only check must pass before the first mutation.
    release = publisher.preflight()
    release = publisher.stage(release)
    for file in files:  # Manifest last.
        publisher.upload(release, file)
    publisher.verify(files)
    publisher.finish()
    print('Release verified as Latest. Publishing is not atomic; rerun safely after partial failure.')


if __name__ == '__main__':
    try:
        main()
    except (Failure, KeyError, ValueError, OSError) as error:
        # Unexpected JSON/config errors must not dump potentially sensitive content.
        print(str(error) if isinstance(error, Failure) else 'Invalid local files or API response; check configuration.', file=sys.stderr)
        sys.exit(1)
