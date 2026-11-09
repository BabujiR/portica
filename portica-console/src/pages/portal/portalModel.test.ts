import { describe, expect, it } from 'vitest';
import type { PortalCatalog } from '../../api/portal';
import { catalogEnvironments, curlFor, fillPath, parseHeaders, pathParameters, searchCatalog, sortOperations } from './portalModel';

const catalog: PortalCatalog = {
  organization: 'acme',
  products: [],
  apis: [
    { name: 'pets', displayName: 'Pet Store', description: 'Dogs and cats', products: ['pets-free'], documented: true,
      deployments: [{ environment: 'prod', revision: 2, basePath: '/pets', url: 'https://gw/acme/prod/pets' },
        { environment: 'dev', revision: 3, basePath: '/pets', url: 'https://gw/acme/dev/pets' }] },
    { name: 'orders', displayName: 'Orders', products: ['shop'], documented: false, deployments: [] },
  ],
  mcpServers: [{ name: 'crm', displayName: 'CRM tools', type: 'REMOTE', products: ['shop'],
    endpoints: [{ environment: 'test', url: 'https://gw/acme/test/mcp/crm' }] }],
};

describe('portal model', () => {
  it('finds and fills path parameters', () => {
    expect(pathParameters('/pets/{petId}/toys/{toyId}')).toEqual(['petId', 'toyId']);
    expect(fillPath('/pets/{petId}/toys/{toyId}', { petId: 'a b' })).toBe('/pets/a%20b/toys/{toyId}');
  });

  it('parses header lines', () => {
    expect(parseHeaders('x-api-key: k1\n\nbad line\nAccept:  application/json \nno space: ok')).toEqual({
      'x-api-key': 'k1', Accept: 'application/json',
    });
  });

  it('builds a copyable curl command', () => {
    expect(curlFor('https://gw/acme/dev/pets/', { method: 'post', path: '/pets', headers: { 'x-api-key': 'k' }, body: '{"name":"rex"}' }))
      .toBe(`curl -i -X POST -H 'x-api-key: k' --data '{"name":"rex"}' https://gw/acme/dev/pets/pets`);
    expect(curlFor('https://gw', { method: 'GET', path: "/it's", headers: {} })).toBe(`curl -i 'https://gw/it'\\''s'`);
  });

  it('sorts operations by path and method', () => {
    const sorted = sortOperations([
      { method: 'DELETE', path: '/pets/{id}' }, { method: 'get', path: '/pets/{id}' }, { method: 'POST', path: '/pets' },
    ]);
    expect(sorted.map((o) => `${o.method} ${o.path}`)).toEqual(['POST /pets', 'get /pets/{id}', 'DELETE /pets/{id}']);
  });

  it('searches the catalog and lists environments', () => {
    expect(searchCatalog(catalog, 'cats').apis.map((a) => a.name)).toEqual(['pets']);
    expect(searchCatalog(catalog, 'shop').mcpServers.map((s) => s.name)).toEqual(['crm']);
    expect(searchCatalog(catalog, ' ')).toBe(catalog);
    expect(catalogEnvironments(catalog)).toEqual(['dev', 'prod', 'test']);
  });
});
