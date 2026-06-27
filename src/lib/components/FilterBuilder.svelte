<script lang="ts">
	import { query } from '$lib/stores/query.svelte';
	import FilterGroupCard from './FilterGroupCard.svelte';
</script>

<FilterGroupCard group={query.filters} path={[]} tables={query.tables} depth={0} />
